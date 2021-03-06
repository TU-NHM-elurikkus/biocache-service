/**************************************************************************
 *  Copyright (C) 2010 Atlas of Living Australia
 *  All Rights Reserved.
 *
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package au.org.ala.biocache.dao;

import au.com.bytecode.opencsv.CSVWriter;
import au.org.ala.biocache.Config;
import au.org.ala.biocache.RecordWriter;
import au.org.ala.biocache.Store;
import au.org.ala.biocache.dto.*;
import au.org.ala.biocache.index.IndexDAO;
import au.org.ala.biocache.index.SolrIndexDAO;
import au.org.ala.biocache.model.Qid;
import au.org.ala.biocache.service.*;
import au.org.ala.biocache.stream.OptionalZipOutputStream;
import au.org.ala.biocache.util.*;
import au.org.ala.biocache.util.thread.EndemicCallable;
import au.org.ala.biocache.vocab.ErrorCode;
import au.org.ala.biocache.writer.CSVRecordWriter;
import au.org.ala.biocache.writer.ShapeFileRecordWriter;
import au.org.ala.biocache.writer.TSVRecordWriter;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.googlecode.ehcache.annotations.Cacheable;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.io.output.CloseShieldOutputStream;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.beans.DocumentObjectBinder;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.response.*;
import org.apache.solr.client.solrj.response.FacetField.Count;
import org.apache.solr.client.solrj.response.RangeFacet.Numeric;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.inject.Inject;
import javax.servlet.ServletOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOLR implementation of SearchDao. Uses embedded SOLR server (can be a memory hog).
 *
 * @see au.org.ala.biocache.dao.SearchDAO
 * @author "Nick dos Remedios <Nick.dosRemedios@csiro.au>"
 */
@Component("searchDao")
public class SearchDAOImpl implements SearchDAO {

    /** log4 j logger */
    private static final Logger logger = Logger.getLogger(SearchDAOImpl.class);

    public static final String DECADE_FACET_START_DATE = "1850-01-01T00:00:00Z";
    public static final String DECADE_PRE_1850_LABEL = "before";
    public static final String SOLR_DATE_FORMAT = "yyyy-MM-dd'T'hh:mm:ss'Z'";
    public static final String OCCURRENCE_YEAR_INDEX_FIELD = "occurrence_year";

    //sensitive fields and their non-sensitive replacements
    private static final String[] sensitiveCassandraHdr = {"decimalLongitude", "decimalLatitude", "verbatimLocality"};
    private static final String[] sensitiveSOLRHdr = {"sensitive_longitude","sensitive_latitude","sensitive_locality"};
    private static final String[] notSensitiveCassandraHdr = {"decimalLongitude.p", "decimalLatitude.p", "locality"};
    private static final String[] notSensitiveSOLRHdr = {"longitude","latitude","locality"};

    /** SOLR server instance */
    protected volatile SolrServer server;
    protected SolrRequest.METHOD queryMethod;
    /** Limit search results - for performance reasons */
    @Value("${download.max:500000}")
    protected Integer MAX_DOWNLOAD_SIZE = 500000;
    /** Throttle value used to split up large downloads from Solr.
     * Randomly set to a range of 100% up to 200% of the value given here in each case.
     **/
    @Value("${download.throttle.ms:50}")
    protected Integer throttle = 50;
    /** Batch size for a download */
    @Value("${download.batch.size:500}")
    protected Integer downloadBatchSize = 500;
    /** The size of an internal fixed length blocking queue used to parallelise
     * reading from Solr using 'solr.downloadquery.maxthreads' producers before
     * writing from the queue using a single consumer thread.
     * <br> This should be set large enough so that writing to the output stream
     * is the limiting factor, but not so large as to allow OutOfMemoryError's to
     * occur due to its memory usage.
     **/
    @Value("${download.internal.queue.size:1000}")
    protected Integer resultsQueueLength;
    /** Maximum total time for downloads to be execute. Defaults to 1 week (604,800,000ms) */
    @Value("${download.max.execute.time:604800000}")
    protected Long downloadMaxTime = 604800000L;
    /** Maximum total time for downloads to be allowed to normally complete before they are aborted,
     * once all of the Solr/etc. queries have been completed or aborted and the RecordWriter is reading the remaining download.internal.queue.size items off the queue.
     * Defaults to 5 minutes (300,000ms) */
    @Value("${download.max.completion.time:300000}")
    protected Long downloadMaxCompletionTime = 300000L;
    public static final String NAMES_AND_LSID = "names_and_lsid";
    public static final String COMMON_NAME_AND_LSID = "common_name_and_lsid";
    protected static final String DECADE_FACET_NAME = "decade";
    protected static final Integer FACET_PAGE_SIZE = 1000;
    protected static final String QUOTE = "\"";
    protected static final char[] CHARS = {' ',':'};
    protected static final String RANGE_SUFFIX = "_RNG";

    private String spatialField = "geohash";

    //Patterns that are used to prepare a SOLR query for execution
    protected Pattern lsidPattern = Pattern.compile("(^|\\s|\"|\\(|\\[|')lsid:\"?([a-zA-Z0-9/\\.:\\-_]*)\"?");
    protected Pattern urnPattern = Pattern.compile("urn:[a-zA-Z0-9\\.:-]*");
    protected Pattern httpPattern = Pattern.compile("http:[a-zA-Z0-9/\\.:\\-_]*");
    protected Pattern spacesPattern = Pattern.compile("[^\\s\"\\(\\)\\[\\]{}']+|\"[^\"]*\"|'[^']*'");
    protected Pattern uidPattern = Pattern.compile("(?:[\"]*)?([a-z_]*_uid:)([a-z0-9]*)(?:[\"]*)?");
    protected Pattern spatialPattern = Pattern.compile(spatialField+":\"Intersects\\([a-zA-Z=\\-\\s0-9\\.\\,():]*\\)\\\"");
    protected Pattern qidPattern = QidCacheDAO.qidPattern;//Pattern.compile("qid:[0-9]*");
    protected Pattern termPattern = Pattern.compile("([a-zA-z_]+?):((\".*?\")|(\\\\ |[^: \\)\\(])+)"); // matches foo:bar, foo:"bar bash" & foo:bar\ bash
    protected Pattern indexFieldPatternMatcher = java.util.regex.Pattern.compile("[a-z_0-9]{1,}:");
    protected Pattern layersPattern = Pattern.compile("(el|cl)[0-9abc]+");
    protected Pattern taxaPattern = Pattern.compile("(^|\\s|\"|\\(|\\[|')taxa:\"?([a-zA-Z0-9\\s\\(\\)\\.:\\-_]*)\"?");

    /**solr connection retry limit **/
    @Value("${solr.server.retry.max:6}")
    protected int maxRetries = 6;
    /**solr connection wait time between retries in ms **/
    @Value("${solr.server.retry.wait:50}")
    protected long retryWait = 50;
    /**solr index version refresh time in ms, 5*60*1000 **/
    @Value("${solr.server.indexVersion.refresh:300000}")
    protected int solrIndexVersionRefreshTime = 300000;

    @Value("${shapefile.tmp.dir:/data/biocache-download/tmp}")
    protected String tmpShapefileDir;

    @Value("${download.unzipped.limit:10000}")
    protected Integer unzippedLimit;

    /** Download properties */
    protected DownloadFields downloadFields;

    @Inject
    protected SearchUtils searchUtils;

    @Inject
    protected CollectionsCache collectionCache;

    @Inject
    protected AbstractMessageSource messageSource;

    @Inject
    protected SpeciesLookupService speciesLookupService;

    @Inject
    protected AuthService authService;

    @Inject
    protected LayersService layersService;

    @Inject
    protected QidCacheDAO qidCacheDao;

    @Inject
    protected RangeBasedFacets rangeBasedFacets;

    @Inject
    protected SpeciesCountsService speciesCountsService;

    @Inject
    protected SpeciesImageService speciesImageService;

    @Inject
    protected ListsService listsService;

    @Value("${media.store.local:true}")
    protected Boolean usingLocalMediaRepo = true;

    /** Max number of threads to use in parallel for endemic queries */
    @Value("${endemic.query.maxthreads:30}")
    protected Integer maxEndemicQueryThreads = 30;

    /** Max number of threads to use in parallel for large solr download queries */
    @Value("${solr.downloadquery.maxthreads:30}")
    protected Integer maxSolrDownloadThreads = 30;

    /** The time (ms) to wait for the blocking queue to have new capacity before timing out. */
    @Value("${solr.downloadquery.writertimeout:60000}")
    protected Long writerTimeoutWaitMillis = 60000L;

    /** The time (ms) to wait between checking if interrupts have occurred or all of the download tasks have completed. */
    @Value("${solr.downloadquery.busywaitsleep:100}")
    protected Long downloadCheckBusyWaitSleep = 100L;

    /** thread pool for multipart endemic queries */
    private volatile ExecutorService endemicExecutor = null;

    /** thread pool for faceted solr queries */
    private volatile ExecutorService solrExecutor = null;

    /** should we check download limits */
    @Value("${check.download.limits:false}")
    protected boolean checkDownloadLimits = false;

    @Value("${term.query.limit:1000}")
    protected Integer termQueryLimit = 1000;

    /** Comma separated list of solr fields that need to have the authService substitute values if they are used in a facet. */
    @Value("${auth.substitution.fields:}")
    protected String authServiceFields = "";

    @Value("${media.url:http://biocache.ala.org.au/biocache-media/}")
    public static String biocacheMediaUrl = "http://biocache.ala.org.au/biocache-media/";

    @Value("${media.dir:/data/biocache-media/}")
    public static String biocacheMediaDir = "/data/biocache-media/";

    private volatile Set<IndexFieldDTO> indexFields = null;
    private volatile Map<String, IndexFieldDTO> indexFieldMap = null;

    private final Map<String, StatsIndexFieldDTO> rangeFieldCache = new HashMap<String, StatsIndexFieldDTO>();

    private Set<String> authIndexFields = null;

    /** SOLR index version for client app caching use. */
    private volatile long solrIndexVersion = 0;
    /** last time SOLR index version was refreshed */
    private volatile long solrIndexVersionTime = 0;
    /**
     * Lock object used to synchronize updates to the solr index version
     */
    private final Object solrIndexVersionLock = new Object();

    @Value("${wms.colour:0x00000000}")
    protected int DEFAULT_COLOUR;

    /**
     * Initialise the SOLR server instance
     */
    public SearchDAOImpl() {}

    private SolrServer getServer(){
        SolrServer result = server;
        if(result == null) {
            synchronized(this) {
                result = server;
                if(result == null) {
                    int retry = 0;
                    while(result == null && retry < maxRetries){
                        retry ++;
                        if (retryWait > 0) {
                            try {
                                Thread.sleep(retryWait);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        result = server = initServer();
                    }
                }
            }
        }
        return result;
    }

    private SolrServer initServer() {
        SolrServer result = server;
        if (result == null) {
            synchronized(this) {
                result = server;
                if(result == null) {
                    try {
                        // use the solr server that has been in the biocache-store...
                        SolrIndexDAO dao = (SolrIndexDAO) au.org.ala.biocache.Config
                            .getInstance(IndexDAO.class);
                        dao.init();
                        result = server = dao.solrServer();
                        queryMethod = SolrRequest.METHOD.GET;

                        logger.debug("The server " + result.getClass());

                        //CAUSING THE HANG....
                        downloadFields = new DownloadFields(getIndexedFields(), messageSource);
                    } catch (Exception ex) {
                        logger.error("Error initialising embedded SOLR server: " + ex.getMessage(), ex);
                    }
                }
            }
        }
        return result;
    }

    public Set<String> getAuthIndexFields(){
        if(authIndexFields == null){
            //set up the hash set of the fields that need to have the authentication service substitute

            logger.debug("Auth substitution fields to use: " + authServiceFields);

            authIndexFields = new java.util.HashSet<String>();
            CollectionUtils.mergeArrayIntoCollection(authServiceFields.split(","), authIndexFields);
        }
        return authIndexFields;
    }

    public void refreshCaches() {
        collectionCache.updateCache();
        //empties the range cache to allow the settings to be recalculated.
        rangeFieldCache.clear();
        try {
            //update indexed fields
            downloadFields = new DownloadFields(getIndexedFields(true), messageSource);
        } catch(Exception e) {
            logger.error("Unable to refresh cache.", e);
        }
        speciesImageService.resetCache();
        speciesCountsService.resetCache();
    }

    /**
     * Returns a list of species that are endemic to the supplied region. Values are cached
     * due to the "expensive" operation.
     */
    @Cacheable(cacheName = "endemicCache")
    public List<FieldResultDTO> getEndemicSpecies(SpatialSearchRequestParams requestParams) throws Exception{
        ExecutorService nextExecutor = getEndemicThreadPoolExecutor();
        // 1)get a list of species that are in the WKT

        logger.debug("Starting to get Endemic Species...");

        List<FieldResultDTO> list1 = getValuesForFacet(requestParams); //new ArrayList(Arrays.asList(getValuesForFacets(requestParams)));

        logger.debug("Retrieved species within area...(" + list1.size() + ")");

        // 2)get a list of species that occur in the inverse WKT

        String reverseQuery = SpatialUtils.getWKTQuery(spatialField, requestParams.getWkt(), true);//"-geohash:\"Intersects(" +wkt + ")\"";

        logger.debug("The reverse query:" + reverseQuery);

        requestParams.setWkt(null);

        int i = 0, localterms = 0;

        String facet = requestParams.getFacets()[0];
        String[] originalFqs = requestParams.getFq();
        //add the negated WKT query to the fq
        originalFqs= (String[])ArrayUtils.add(originalFqs ,reverseQuery);
        List<Future<List<FieldResultDTO>>> threads = new ArrayList<Future<List<FieldResultDTO>>>();
        //batch up the rest of the world query so that we have fqs based on species we want to test for. This should improve the performance of the endemic services.
        while(i < list1.size()){
            StringBuffer sb = new StringBuffer();
            while((localterms == 0 || localterms % termQueryLimit != 0) && i < list1.size()){
                if(localterms > 0) {
                    sb.append(" OR ");
                }
                sb.append(facet).append(":").append(ClientUtils.escapeQueryChars(list1.get(i).getFieldValue()));
                i++;
                localterms++;
            }
            String newfq = sb.toString();
            if(localterms ==1)
                newfq = newfq+ " OR " + newfq; //cater for the situation where there is only one term.  We don't want the term to be escaped again
            localterms=0;
            logger.debug("FQ = " + newfq);
            SpatialSearchRequestParams srp = new SpatialSearchRequestParams();
            BeanUtils.copyProperties(requestParams, srp);
            srp.setFq((String[])ArrayUtils.add(originalFqs, newfq));
            int batch = i / termQueryLimit;
            EndemicCallable callable = new EndemicCallable(srp, batch,this);
            threads.add(nextExecutor.submit(callable));
        }
        for(Future<List<FieldResultDTO>> future: threads){
            List<FieldResultDTO> list = future.get();
            if(list != null) {
                list1.removeAll(list);
            }
        }

        logger.debug("Determined final endemic list (" + list1.size() + ")...");

        return list1;
    }

    /**
     * @return An instance of ExecutorService used to concurrently execute multiple endemic queries.
     */
    private ExecutorService getEndemicThreadPoolExecutor() {
        ExecutorService nextExecutor = endemicExecutor;
        if(nextExecutor == null){
            synchronized(this) {
                nextExecutor = endemicExecutor;
                if(nextExecutor == null) {
                    nextExecutor = endemicExecutor = Executors.newFixedThreadPool(
                                                                getMaxEndemicQueryThreads(),
                                                                new ThreadFactoryBuilder().setNameFormat("biocache-endemic-%d")
                                                                .setPriority(Thread.MIN_PRIORITY).build());
                }
            }
        }
        return nextExecutor;
    }

    /**
     * @return An instance of ExecutorService used to concurrently execute multiple solr queries.
     */
    private ExecutorService getSolrThreadPoolExecutor() {
        ExecutorService nextExecutor = solrExecutor;
        if(nextExecutor == null){
            synchronized(this) {
                nextExecutor = solrExecutor;
                if(nextExecutor == null) {
                    nextExecutor = solrExecutor = Executors.newFixedThreadPool(
                                                                getMaxEndemicQueryThreads(),
                                                                new ThreadFactoryBuilder().setNameFormat("biocache-solr-%d")
                                                                .setPriority(Thread.MIN_PRIORITY).build());
                }
            }
        }
        return nextExecutor;
    }

    /**
     * (Endemic)
     *
     * Returns a list of species that are only within a subQuery.
     *
     * The subQuery is a subset of parentQuery.
     */
    public List<FieldResultDTO> getSubquerySpeciesOnly(SpatialSearchRequestParams subQuery, SpatialSearchRequestParams parentQuery) throws Exception{
        ExecutorService nextExecutor = getEndemicThreadPoolExecutor();
        // 1)get a list of species that are in the WKT

        logger.debug("Starting to get Endemic Species...");

        subQuery.setFacet(true);
        subQuery.setFacets(parentQuery.getFacets());
        List<FieldResultDTO> list1 = getValuesForFacet(subQuery);

        logger.debug("Retrieved species within area...(" + list1.size() + ")");


        int i = 0, localterms = 0;

        String facet = parentQuery.getFacets()[0];
        String[] originalFqs = parentQuery.getFq();
        List<Future<List<FieldResultDTO>>> futures = new ArrayList<Future<List<FieldResultDTO>>>();
        //batch up the rest of the world query so that we have fqs based on species we want to test for.
        // This should improve the performance of the endemic services.
        while(i < list1.size()){
            StringBuffer sb = new StringBuffer();
            while((localterms == 0 || localterms % termQueryLimit != 0) && i < list1.size()){
                if(localterms > 0) {
                    sb.append(" OR ");
                }
                String value = list1.get(i).getFieldValue();
                if (facet.equals(NAMES_AND_LSID)) {
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    value = "\"" + ClientUtils.escapeQueryChars(value) + "\"";
                } else {
                    value = ClientUtils.escapeQueryChars(value);
                }
                sb.append(facet).append(":").append(value);
                i++;
                localterms++;
            }
            String newfq = sb.toString();
            if(localterms ==1)
                newfq = newfq+ " OR " + newfq; //cater for the situation where there is only one term.  We don't want the term to be escaped again
            localterms=0;
            SpatialSearchRequestParams srp = new SpatialSearchRequestParams();
            BeanUtils.copyProperties(parentQuery, srp);
            srp.setFq((String[])ArrayUtils.add(originalFqs, newfq));
            int batch = i / termQueryLimit;
            EndemicCallable callable = new EndemicCallable(srp, batch,this);
            futures.add(nextExecutor.submit(callable));
        }

        Collections.sort(list1);
        for(Future<List<FieldResultDTO>> future: futures){
            List<FieldResultDTO> list = future.get();
            if(list != null) {
                for (FieldResultDTO find : list) {
                    int idx = Collections.binarySearch(list1, find);
                    //remove if sub query count < parent query count
                    if (idx >= 0 && list1.get(idx).getCount() < find.getCount()) {
                        list1.remove(idx);
                    }
                }
            }
        }
        logger.debug("Determined final endemic list (" + list1.size() + ")...");
        return list1;
    }

    /**
     * Returns the values and counts for a single facet field.
     */
    public List<FieldResultDTO> getValuesForFacet(SpatialSearchRequestParams requestParams) throws Exception{
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        writeFacetToStream(requestParams, true, false, false, false, outputStream, null);
        outputStream.flush();
        outputStream.close();
        String includedValues = outputStream.toString();
        includedValues = includedValues == null ? "" : includedValues;
        String[] values = includedValues.split("\n");
        List<FieldResultDTO> list = new ArrayList<FieldResultDTO>();
        boolean first = true;
        for(String value: values){
            if(first){
                first = false;
            } else {
                int idx = value.lastIndexOf(",");
                //handle values enclosed in "
                list.add(new FieldResultDTO(value.substring(0,idx), Long.parseLong(value.substring(idx+1).replace("\"",""))));
            }
        }
        return list;
  }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#findByFulltextSpatialQuery
     */
    @Override
    public SearchResultDTO findByFulltextSpatialQuery(SpatialSearchRequestParams searchParams, Map<String,String[]> extraParams) {
        return findByFulltextSpatialQuery(searchParams,false,extraParams);
    }

    /**
     * Main search query method.
     *
     * @param searchParams
     * @param includeSensitive
     * @param extraParams
     * @return
     */
    @Override
    public SearchResultDTO findByFulltextSpatialQuery(SpatialSearchRequestParams searchParams, boolean includeSensitive, Map<String,String[]> extraParams) {
        SearchResultDTO searchResults = new SearchResultDTO();
        SpatialSearchRequestParams original = new SpatialSearchRequestParams();
        BeanUtils.copyProperties(searchParams, original);
        try {
            formatSearchQuery(searchParams);
            //add context information
            updateQueryContext(searchParams);
            String queryString = buildSpatialQueryString(searchParams);
            SolrQuery solrQuery = initSolrQuery(searchParams, true, extraParams); // general search settings
            solrQuery.setQuery(queryString);

            QueryResponse qr = runSolrQuery(solrQuery, searchParams);
            //need to set the original q to the processed value so that we remove the wkt etc that is added from paramcache object
            Class resultClass = includeSensitive? au.org.ala.biocache.dto.SensitiveOccurrenceIndex.class : OccurrenceIndex.class;
            searchResults = processSolrResponse(original, qr, solrQuery, resultClass);
            searchResults.setQueryTitle(searchParams.getDisplayString());
            searchResults.setUrlParameters(original.getUrlParams());

            //need to update fq to remove wkt that may be added. it may not be the last fq
            String [] fqs = searchParams.getFq();
            if (StringUtils.isNotEmpty(searchParams.getWkt())) {
                for (int i = fqs.length - 1; i >= 0; i--) {
                    if (fqs[i].startsWith(spatialField + ":") || fqs[i].startsWith("(" + spatialField + ":")) {
                        fqs = (String[]) ArrayUtils.remove(fqs, i);
                        break;
                    }
                }
            }
            searchParams.setFq(fqs);

            //now update the fq display map...
            searchResults.setActiveFacetMap(searchUtils.addFacetMap(searchParams.getFq(), searchParams.getQc(), getAuthIndexFields()));
            searchResults.setFieldStats(qr.getFieldStatsInfo());

            if(qr.getFacetPivot() != null) {
                Map<String, List<PivotField>> pivotMap = new HashMap<>();

                for(Map.Entry<String, List<PivotField>> entry : qr.getFacetPivot()) {
                    pivotMap.put(entry.getKey(), entry.getValue());
                }

                searchResults.setFacetPivot(pivotMap);
            }

            logger.info("spatial search query: " + queryString);
        } catch (Exception ex) {
            logger.error("Error executing query with requestParams: " + searchParams.toString()+ " EXCEPTION: " + ex.getMessage(), ex);
            searchResults.setStatus("ERROR"); // TODO also set a message field on this bean with the error message(?)
            searchResults.setErrorMessage(ex.getMessage());
        }

        return searchResults;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#writeSpeciesCountByCircleToStream(au.org.ala.biocache.dto.SpatialSearchRequestParams, String, javax.servlet.ServletOutputStream)
     */
    public int writeSpeciesCountByCircleToStream(SpatialSearchRequestParams searchParams, String speciesGroup, ServletOutputStream out) throws Exception {

        //get the species counts:
        logger.debug("Writing CSV file for species count by circle");

        searchParams.setPageSize(-1);
        List<TaxaCountDTO> species = findAllSpeciesByCircleAreaAndHigherTaxa(searchParams, speciesGroup);
        logger.debug("There are " + species.size() + "records being downloaded");

        try(CSVWriter csvWriter = new CSVWriter(new OutputStreamWriter(new CloseShieldOutputStream(out), StandardCharsets.UTF_8), '\t', '"');) {
            csvWriter.writeNext(new String[]{
                        "Taxon ID",
                        "Kingdom",
                        "Family",
                        "Scientific name",
                        "Common name",
                        "Record count",});
            int count = 0;
            for (TaxaCountDTO item : species) {

                String[] record = new String[]{
                    item.getGuid(),
                    item.getKingdom(),
                    item.getFamily(),
                    item.getName(),
                    item.getCommonName(),
                    item.getCount().toString()
                };

                csvWriter.writeNext(record);
                count++;
            }
            csvWriter.flush();
            return count;
        }
    }

    /**
     * Writes the values for the first supplied facet to output stream
     *
     * @param includeCount true when the count should be included in the download
     * @param lookupName true when a name lsid should be looked up in the bie
     *
     */
    public void writeFacetToStream(SpatialSearchRequestParams searchParams, boolean includeCount, boolean lookupName, boolean includeSynonyms, boolean includeLists, OutputStream out, DownloadDetailsDTO dd) throws Exception {
        //set to unlimited facets
        searchParams.setFlimit(-1);
        formatSearchQuery(searchParams);
        //add the context information
        updateQueryContext(searchParams);
        String queryString = buildSpatialQueryString(searchParams);
        SolrQuery solrQuery = initSolrQuery(searchParams,false,null);
        solrQuery.setQuery(queryString);

        //don't want any results returned
        solrQuery.setRows(0);
        searchParams.setPageSize(0);
        solrQuery.setFacetLimit(FACET_PAGE_SIZE);
        int offset = 0;
        boolean shouldLookup = lookupName && (searchParams.getFacets()[0].contains("_guid")||searchParams.getFacets()[0].contains("_lsid"));

        QueryResponse qr = runSolrQuery(solrQuery, searchParams);
        logger.debug("Retrieved facet results from server...");

        if (qr.getResults().getNumFound() > 0) {
            FacetField ff = qr.getFacetField(searchParams.getFacets()[0]);

            //write the header line
            if(ff != null){
                String[] header = new String[]{ff.getName()};
               // out.write(ff.getName().getBytes());
                if(shouldLookup){
                    header = speciesLookupService.getHeaderDetails(ff.getName(), includeCount, includeSynonyms);
                }
                else if(includeCount){
                    //out.write(",Count".getBytes());
                    header = (String[])ArrayUtils.add(header, "count");
                }
                if (includeLists) {
                    header = (String[]) ArrayUtils.addAll(header, listsService.getTypes().toArray(new String[]{}));
                }

                CSVRecordWriter writer = new CSVRecordWriter(new CloseShieldOutputStream(out), header);
                try {
                    boolean addedNullFacet = false;

                    //out.write("\n".getBytes());
                    //PAGE through the facets until we reach the end.
                    //do not continue when null facet is already added and the next facet is only null
                    while (ff.getValueCount() > 1 || !addedNullFacet || (ff.getValueCount() == 1 && ff.getValues().get(0).getName() != null)) {
                        //process the "species_guid_ facet by looking up the list of guids
                        if (shouldLookup) {
                            List<String> guids = new ArrayList<String>();
                            List<Long> counts = new ArrayList<Long>();
                            List<String[]> speciesLists = new ArrayList<String[]>();
                            logger.debug("Downloading " + ff.getValueCount() + " species guids");

                            for (FacetField.Count value : ff.getValues()) {
                                //only add null facet once
                                if (value.getName() == null) addedNullFacet = true;
                                if (value.getCount() == 0 || (value.getName() == null && addedNullFacet)) continue;

                                guids.add(value.getName());
                                if (includeCount) {
                                    counts.add(value.getCount());
                                }

                                //Only want to send a sub set of the list so that the URI is not too long for BIE
                                if (guids.size() == 30) {
                                    //now get the list of species from the web service TODO may need to move this code
                                    //handle null values being returned from the service...
                                    writeTaxonDetailsToStream(guids, counts, includeCount, includeSynonyms, includeLists, writer);
                                    guids.clear();
                                    counts.clear();
                                }
                            }
                            //now write any guids that remain at the end of the looping
                            writeTaxonDetailsToStream(guids, counts, includeCount, includeSynonyms, includeLists, writer);
                        } else {
                            //default processing of facets
                            for (FacetField.Count value : ff.getValues()) {
                                //only add null facet once
                                if (value.getName() == null) addedNullFacet = true;
                                if (value.getCount() == 0 || (value.getName() == null && addedNullFacet)) continue;

                                String name = value.getName() != null ? value.getName() : "";
                                String[] row = includeCount ? new String[]{name, Long.toString(value.getCount())} : new String[]{name};
                                writer.write(row);
                            }
                        }
                        offset += FACET_PAGE_SIZE;
                        if (dd != null) {
                            dd.updateCounts(FACET_PAGE_SIZE);
                        }

                        //get the next values
                        solrQuery.remove("facet.offset");
                        solrQuery.add("facet.offset", Integer.toString(offset));
                        qr = runSolrQuery(solrQuery, searchParams);
                        ff = qr.getFacetField(searchParams.getFacets()[0]);
                    }
                } finally {
                    writer.finalise();
                }
            }
        }
    }
    /**
     * Writes additional taxon information to the stream. It performs bulk lookups to the
     * BIE in order to obtain extra classification information
     * @param guids The guids to lookup
     * @param counts The occurrence counts for each guid if "includeCounts = true"
     * @param includeCounts Whether or not to include the occurrence counts in the download
     * @param includeSynonyms whether or not to include the synonyms in the download - when
     * true this will perform additional lookups in the BIE
     * @param writer The CSV writer to write to.
     * @throws Exception
     */
    private void writeTaxonDetailsToStream(List<String> guids, List<Long> counts, boolean includeCounts, boolean includeSynonyms, boolean includeLists, CSVRecordWriter writer) throws Exception {
        List<String[]> values = speciesLookupService.getSpeciesDetails(guids, counts, includeCounts, includeSynonyms, includeLists);
        for(String[] value : values){
            writer.write(value);
        }
    }

    /**
     * Writes all the distinct latitude and longitude in the index to the supplied
     * output stream.
     *
     * @param out
     * @throws Exception
     */
    public void writeCoordinatesToStream(SearchRequestParams searchParams,OutputStream out) throws Exception{
        //generate the query to obtain the lat,long as a facet
        SearchRequestParams srp = new SearchRequestParams();
        SearchUtils.setDefaultParams(srp);
        srp.setFacets(searchParams.getFacets());

        SolrQuery solrQuery = initSolrQuery(srp,false,null);
        //We want all the facets so we can dump all the coordinates
        solrQuery.setFacetLimit(-1);
        solrQuery.setFacetSort("count");
        solrQuery.setRows(0);
        solrQuery.setQuery(searchParams.getQ());

        QueryResponse qr = runSolrQuery(solrQuery, srp);
        if (qr.getResults().size() > 0) {
            FacetField ff = qr.getFacetField(searchParams.getFacets()[0]);
            if(ff != null && ff.getValueCount() > 0){
                out.write("latitude,longitude\n".getBytes());
                //write the facets to file
                for(FacetField.Count value : ff.getValues()){
                    //String[] slatlon = value.getName().split(",");
                    if (value.getName() != null && value.getCount() > 0) {
                        out.write(value.getName().getBytes());
                        out.write("\n".getBytes());
                    }
                }
            }
        }
    }

    /**
     * Writes the index fields to the supplied output stream in CSV format.
     *
     * DM: refactored to split the query by month to improve performance.
     * Further enhancements possible:
     * 1) Multi threaded
     * 2) More filtering, by year or decade..
     *
     * @param downloadParams
     * @param out
     * @param includeSensitive
     * @throws Exception
     */
    public ConcurrentMap<String, AtomicInteger> writeResultsFromIndexToStream(final DownloadRequestParams downloadParams,
                                                                              OutputStream out,
                                                                              boolean includeSensitive,
                                                                              final DownloadDetailsDTO dd,
                                                                              boolean checkLimit) throws Exception {

        ExecutorService nextExecutor = getSolrThreadPoolExecutor();
        long start = System.currentTimeMillis();
        final ConcurrentMap<String, AtomicInteger> uidStats = new ConcurrentHashMap<>();
        getServer();
        try {
            SolrQuery solrQuery = new SolrQuery();
            formatSearchQuery(downloadParams);

            String dFields = downloadParams.getFields();

            if(includeSensitive){
                //include raw latitude and longitudes
                if (dFields.contains("decimalLatitude.p")) {
                    dFields = dFields.replaceFirst("decimalLatitude.p", "sensitive_latitude,sensitive_longitude,decimalLatitude.p");
                } else if (dFields.contains("decimalLatitude")){
                    dFields = dFields.replaceFirst("decimalLatitude", "sensitive_latitude,sensitive_longitude,decimalLatitude");
                }
                if (dFields.contains(",locality,")) {
                    dFields = dFields.replaceFirst(",locality,", ",locality,sensitive_locality,");
                }
                if (dFields.contains(",locality.p,")) {
                    dFields = dFields.replaceFirst(",locality.p,", ",locality.p,sensitive_locality,");
                }
            }

            StringBuilder sb = new StringBuilder(dFields);
            if(!downloadParams.getExtra().isEmpty()) {
                sb.append(",").append(downloadParams.getExtra());
            }

            String[] requestedFields = sb.toString().split(",");
            List<String>[] indexedFields;
            if (downloadFields == null) {
                logger.debug("(downloadFields == null)");
                // default to include everything
                java.util.List<String> mappedNames = new java.util.LinkedList<String>();
                for (String requestedField : requestedFields) {
                    mappedNames.add(requestedField);
                }

                indexedFields = new List[]{mappedNames, new java.util.LinkedList<String>(), mappedNames, mappedNames};
            } else {
                logger.debug("(downloadFields NOT null)");
                indexedFields = downloadFields.getIndexFields(requestedFields, downloadParams.getDwcHeaders());
            }
            logger.debug("Fields included in download: " + String.join(",", indexedFields[0]));
            logger.debug("Fields excluded from download: " + String.join(",", indexedFields[1]));
            logger.debug("The headers in downloads: " + String.join(",", indexedFields[2]));

            //set the fields to the ones that are available in the index
            String[] fields = indexedFields[0].toArray(new String[]{});
            solrQuery.setFields(fields);
            StringBuilder qasb = new StringBuilder();
            if(!"none".equals(downloadParams.getQa())){
                solrQuery.addField("assertions");
                if(!"all".equals(downloadParams.getQa()) && !"includeall".equals(downloadParams.getQa())) {
                    //add all the qa fields
                    qasb.append(downloadParams.getQa());
                }
            }
            solrQuery.addField("institution_uid")
                .addField("collection_uid")
                .addField("data_resource_uid")
                .addField("data_provider_uid");

            //add context information
            updateQueryContext(downloadParams);
            solrQuery.setQuery(buildSpatialQueryString(downloadParams));
            solrQuery.setFacetMinCount(1);
            solrQuery.setFacetLimit(-1);

            //get the assertion facets to add them to the download fields
            boolean getAssertionsFromFacets = "all".equals(downloadParams.getQa()) || "includeall".equals(downloadParams.getQa());
            SolrQuery monthAssertionsQuery = getAssertionsFromFacets?solrQuery.getCopy().addFacetField("month", "assertions"):solrQuery.getCopy().addFacetField("month");
            if(getAssertionsFromFacets){
                //set the order for the facet to be based on the index - this will force the assertions to be returned in the same order each time
                //based on alphabetical sort.  The number of QA's may change between searches so we can't guarantee that the order won't change
                monthAssertionsQuery.add("f.assertions.facet.sort","index");
            }
            QueryResponse facetQuery = runSolrQuery(monthAssertionsQuery, downloadParams.getFq(), 0, 0, "score", "asc");

            //set the totalrecords for the download details
            dd.setTotalRecords(facetQuery.getResults().getNumFound());

            //use a separately configured and smaller limit when output will be unzipped
            final long maxDownloadSize;
            if (MAX_DOWNLOAD_SIZE > unzippedLimit && out instanceof OptionalZipOutputStream &&
                    ((OptionalZipOutputStream) out).getType() == OptionalZipOutputStream.Type.unzipped) {
                maxDownloadSize = unzippedLimit;
            } else {
                maxDownloadSize = MAX_DOWNLOAD_SIZE;
            }

            if(checkLimit && dd.getTotalRecords() < maxDownloadSize){
                checkLimit = false;
            }

            //get the month facets to add them to the download fields get the assertion facets.
            List<Count> splitByFacet = null;

            for(FacetField facet : facetQuery.getFacetFields()){
                if(facet.getName().equals("assertions") && facet.getValueCount() > 0){
                    qasb.append(getQAFromFacet(facet));
                }
                if(facet.getName().equals("month") && facet.getValueCount() > 0){
                   splitByFacet = facet.getValues();
                }
            }

            if ("includeall".equals(downloadParams.getQa())) {
                qasb = getAllQAFields();
            }

            String qas = qasb.toString();

            //include sensitive fields in the header when the output will be partially sensitive
            final String [] sensitiveFields;
            final String [] notSensitiveFields;
            if (dd.getSensitiveFq() != null) {
                List<String>[] sensitiveHdr = downloadFields.getIndexFields(sensitiveSOLRHdr, downloadParams.getDwcHeaders());

                //header for the output file
                indexedFields[2].addAll(sensitiveHdr[2]);

                //lookup for fields from sensitive queries
                sensitiveFields = org.apache.commons.lang3.ArrayUtils.addAll(indexedFields[0].toArray(new String[]{}),
                        sensitiveHdr[0].toArray(new String[]{}));

                //use general fields when sensitive data is not permitted
                notSensitiveFields = org.apache.commons.lang3.ArrayUtils.addAll(indexedFields[0].toArray(new String[]{}), notSensitiveSOLRHdr);
            } else {
                sensitiveFields = new String[0];
                notSensitiveFields = fields;
            }

            final String[] qaFields = qas.equals("") ? new String[]{} : qas.split(",");
            String[] qaTitles = downloadFields.getHeader(qaFields, false, false);

            String[] header = org.apache.commons.lang3.ArrayUtils.addAll(indexedFields[2].toArray(new String[]{}),qaTitles);

            //retain output header fields and field names for inclusion of header info in the download
            StringBuilder infoFields = new StringBuilder("infoFields");
            for (String h : indexedFields[3]) infoFields.append(",").append(h);
            for (String h : qaFields) infoFields.append(",").append(h);

            StringBuilder infoHeader = new StringBuilder("infoHeaders");
            for (String h : header) infoHeader.append(",").append(h);

            uidStats.put(infoFields.toString(), new AtomicInteger(-1));
            uidStats.put(infoHeader.toString(), new AtomicInteger(-2));

            //construct correct RecordWriter based on the supplied fileType
            final au.org.ala.biocache.RecordWriter rw = downloadParams.getFileType().equals("csv") ?
                    new CSVRecordWriter(new CloseShieldOutputStream(out), header, downloadParams.getSep(), downloadParams.getEsc()) :
                    (downloadParams.getFileType().equals("tsv") ? new TSVRecordWriter(new CloseShieldOutputStream(out), header) :
                            new ShapeFileRecordWriter(tmpShapefileDir, downloadParams.getFile(), new CloseShieldOutputStream(out), (String[]) ArrayUtils.addAll(fields, qaFields)));

            // Requirement to be able to propagate interruptions to all other threads for this execution
            // Doing this via this variable
            final AtomicBoolean interruptFound = new AtomicBoolean(false);

            // Create a fixed length blocking queue for buffering results before they are written
            final BlockingQueue<String[]> queue = new ArrayBlockingQueue<>(resultsQueueLength);
            // Create a sentinel that we can check for reference equality to signal the end of the queue
            final String[] sentinel = new String[0];
            // An implementation of RecordWriter that adds to an in-memory queue
            final RecordWriter concurrentWrapper = new RecordWriter() {
                private AtomicBoolean finalised = new AtomicBoolean(false);
                private AtomicBoolean finalisedComplete = new AtomicBoolean(false);

                @Override
                public void write(String[] nextLine) {
                    try {
                        if (Thread.currentThread().isInterrupted() || interruptFound.get() || finalised.get()) {
                            finalise();
                            return;
                        }
                        while(!queue.offer(nextLine, writerTimeoutWaitMillis, TimeUnit.MILLISECONDS)) {
                            if (Thread.currentThread().isInterrupted() || interruptFound.get() || finalised.get()) {
                                finalise();
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        interruptFound.set(true);
                        logger.error("Queue failed to accept the next record due to a thread interrupt, calling finalise the cleanup: ", e);
                        // If we were interrupted then we should call finalise to cleanup
                        finalise();
                    }
                }

                @Override
                public void finalise() {
                    if (finalised.compareAndSet(false, true)) {
                        try {
                            queue.offer(sentinel, writerTimeoutWaitMillis, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            interruptFound.set(true);
                            logger.error("Queue failed to accept the sentinel in finalise due to a thread interrupt: ", e);
                        } finally {
                            finalisedComplete.set(true);
                        }
                    }
                }

                @Override
                public boolean finalised() {
                    return finalisedComplete.get();
                }

            };

            // A single thread that consumes elements put onto the queue until it sees the sentinel, finalising after the sentinel or an interrupt
            Runnable writerRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        while(true) {
                            if (Thread.currentThread().isInterrupted() || interruptFound.get()) {
                                break;
                            }

                            String[] take = queue.take();
                            // Sentinel object equality check to see if we are done
                            if (take == sentinel || Thread.currentThread().isInterrupted() || interruptFound.get()) {
                                break;
                            }
                            // Otherwise write to the wrapped record writer
                            rw.write(take);
                        }
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        interruptFound.set(true);
                    } catch(Exception e) {
                        // Reuse interruptFound variable to signal that the writer had issues
                        interruptFound.set(true);
                    } finally {
                        rw.finalise();
                    }
                }
            };
            Thread writerThread = new Thread(writerRunnable);
            writerThread.start();
            try {
                if(rw instanceof ShapeFileRecordWriter){
                    dd.setHeaderMap(((ShapeFileRecordWriter)rw).getHeaderMappings());
                }

                //order the query by _docid_ for faster paging
                solrQuery.addSortField("_docid_", ORDER.asc);

                //for each month create a separate query that pages through 500 records per page
                List<SolrQuery> queries = new ArrayList<SolrQuery>();
                if(splitByFacet != null){
                    for(Count facet: splitByFacet){
                        if(facet.getCount() > 0){
                            SolrQuery splitByFacetQuery;
                            //do not add remainderQuery here
                            if (facet.getName() != null) {
                                splitByFacetQuery = solrQuery.getCopy().addFilterQuery(facet.getFacetField().getName() + ":" + facet.getName());
                                splitByFacetQuery.setFacet(false);
                                queries.add(splitByFacetQuery);
                            }

                        }
                    }
                    if (splitByFacet.size() > 0) {
                        SolrQuery remainderQuery = solrQuery.getCopy().addFilterQuery("-" + splitByFacet.get(0).getFacetField().getName() + ":[* TO *]");
                        queries.add(0, remainderQuery);
                    }
                } else {
                    queries.add(0, solrQuery);
                }

                //split into sensitive and non-sensitive queries when
                // - not including all sensitive values
                // - there is a sensitive fq
                final List<SolrQuery> sensitiveQ = new ArrayList<SolrQuery>();
                if (!includeSensitive && dd.getSensitiveFq() != null) {
                    sensitiveQ.addAll(splitQueries(queries, dd.getSensitiveFq(), sensitiveSOLRHdr, notSensitiveSOLRHdr));
                }

                //Set<Future<Integer>> futures = new HashSet<Future<Integer>>();
                final AtomicInteger resultsCount = new AtomicInteger(0);
                final boolean threadCheckLimit = checkLimit;

                List<Callable<Integer>> solrCallables = new ArrayList<>(queries.size());
                // execute each query, writing the results to stream
                for(final SolrQuery splitByFacetQuery: queries){
                    // define a thread
                    Callable<Integer> solrCallable = new Callable<Integer>(){
                        @Override
                        public Integer call() throws Exception {
                            int startIndex = 0;
                            // Randomise the wakeup time so they don't all wakeup on a periodic cycle
                            long localThrottle = throttle + Math.round(Math.random() * throttle);

                            String [] fq = downloadParams.getFq();
                            if (splitByFacetQuery.getFilterQueries() != null && splitByFacetQuery.getFilterQueries().length > 0) {
                                if (fq == null) {
                                    fq = new String[0];
                                }
                                fq = org.apache.commons.lang3.ArrayUtils.addAll(fq, splitByFacetQuery.getFilterQueries());
                            }

                            QueryResponse qr = runSolrQuery(splitByFacetQuery, fq, downloadBatchSize, startIndex, "_docid_", "asc");
                            AtomicInteger recordsForThread = new AtomicInteger(0);

                            logger.debug(splitByFacetQuery.getQuery() + " - results: " + qr.getResults().size());

                            while (qr != null && !qr.getResults().isEmpty()) {
                                logger.debug("Start index: " + startIndex + ", " + splitByFacetQuery.getQuery());

                                int count=0;
                                if (sensitiveQ.contains(splitByFacetQuery)) {
                                    count = processQueryResults(uidStats, sensitiveFields, qaFields, concurrentWrapper, qr, dd, threadCheckLimit, resultsCount, maxDownloadSize);
                                } else {
                                    // write non-sensitive values into sensitive fields when not authorised for their sensitive values
                                    count = processQueryResults(uidStats, notSensitiveFields, qaFields, concurrentWrapper, qr, dd, threadCheckLimit, resultsCount, maxDownloadSize);
                                }
                                recordsForThread.addAndGet(count);
                                startIndex += downloadBatchSize;
                                // we have already set the Filter query the first time the query was constructed
                                // rerun with the same params but different startIndex
                                if(!threadCheckLimit || resultsCount.get() < maxDownloadSize){
                                    if(!threadCheckLimit){
                                        // throttle the download by sleeping
                                        Thread.sleep(localThrottle);
                                    }
                                    qr = runSolrQuery(splitByFacetQuery, null, downloadBatchSize, startIndex, "_docid_", "asc");
                                } else {
                                    qr = null;
                                }
                            }
                            return recordsForThread.get();
                        }
                    };
                    solrCallables.add(solrCallable);
                }

                List<Future<Integer>> futures = new ArrayList<>(solrCallables.size());
                for(Callable<Integer> nextCallable : solrCallables) {
                    futures.add(nextExecutor.submit(nextCallable));
                }

                // Busy wait because we need to be able to respond to an interrupt on any callable
                // and propagate it to all of the others for this particular query
                // Because the executor service is shared to prevent too many concurrent threads being run,
                // this requires a busy wait loop on the main thread to monitor state
                boolean waitAgain = false;
                do {
                    waitAgain = false;
                    for (Future<Integer> future : futures) {
                        if (!future.isDone()) {
                            waitAgain = true;
                            // If one thread finds an interrupt it is propagated to others using the interruptFound AtomicBoolean
                            if (interruptFound.get()) {
                                future.cancel(true);
                            }
                        }
                    }
                    if ((System.currentTimeMillis() - start) > downloadMaxTime) {
                        interruptFound.set(true);
                        break;
                    }
                    Thread.sleep(downloadCheckBusyWaitSleep);
                } while (waitAgain);

                AtomicInteger totalDownload = new AtomicInteger(0);
                for(Future<Integer> future: futures){
                    if (future.isDone()){
                        totalDownload.addAndGet(future.get());
                    } else {
                        // All incomplete futures that survived the loop above are cancelled here
                        future.cancel(true);
                    }
                }

                long finish = System.currentTimeMillis();
                long timeTakenInSecs = (finish-start)/1000;
                if(timeTakenInSecs <= 0) timeTakenInSecs = 1;
                logger.info("Download of " + resultsCount + " records in " + timeTakenInSecs + " seconds. Record/sec: " + resultsCount.intValue()/timeTakenInSecs);
            } finally {
                try {
                    // Once we get here, we need to finalise starting at the concurrent wrapper,
                    // as there are no more non-sentinel records to be added to the queue
                    // This eventually triggers finalisation of the underlying writer when the queue empties
                    // This is a soft shutdown, and hence we wait below for this stage to complete in normal circumstances
                    // Note, this blocks for writerTimeoutWaitMillis trying to legitimately add the sentinel to the end of the queue
                    // We force the sentinel to be added in the hard shutdown phase below
                    concurrentWrapper.finalise();
                } finally {
                    try {
                        final long completionStartTime = System.currentTimeMillis();
                        // Busy wait check for finalised to be called in the RecordWriter or something is interrupted
                        // By this stage, there are at maximum download.internal.queue.size items remaining (default 1000)
                        while(writerThread.isAlive()
                               && !writerThread.isInterrupted()
                               && !interruptFound.get()
                               && !Thread.currentThread().isInterrupted()
                               && !rw.finalised()
                               && !((System.currentTimeMillis() - completionStartTime) > downloadMaxCompletionTime)) {
                            Thread.sleep(downloadCheckBusyWaitSleep);
                        }
                    } finally {
                        try {
                            // Attempt all actions that could trigger the writer thread to finalise, as by this stage we are in hard shutdown mode

                            // Signal that we are in hard shutdown mode
                            interruptFound.set(true);

                            // Add the sentinel or clear the queue and try again until it gets onto the queue
                            // We are in hard shutdown mode, so only priority is that the queue either
                            // gets the sentinel or the thread is interrupted to clean up resources
                            while(!queue.offer(sentinel)) {
                                queue.clear();
                            }

                            // Interrupt the single writer thread
                            writerThread.interrupt();

                            // Explicitly call finalise on the RecordWriter as a backup
                            // In normal circumstances it is called via the sentinel or the interrupt
                            // This will not block if finalise has been called previously in the current three implementations
                            rw.finalise();
                        } finally {
                            // Flush whatever output was still pending for more deterministic debugging
                            out.flush();
                        }
                    }
                }
            }
        } catch (SolrServerException ex) {
            logger.error("Problem communicating with SOLR server while processing download. " + ex.getMessage(), ex);
        }
        return uidStats;
    }

    private int processQueryResults( ConcurrentMap<String, AtomicInteger> uidStats, String[] fields, String[] qaFields, RecordWriter rw, QueryResponse qr, DownloadDetailsDTO dd, boolean checkLimit,AtomicInteger resultsCount, long maxDownloadSize) {
        int count = 0;
        for (SolrDocument sd : qr.getResults()) {
            if(sd.getFieldValue("data_resource_uid") != null &&(!checkLimit || (checkLimit && resultsCount.intValue() < maxDownloadSize))){

                //resultsCount++;
                count++;
                resultsCount.incrementAndGet();

                //add the record
                String[] values = new String[fields.length + qaFields.length];

                //get all the "single" values from the index
                for(int j = 0; j < fields.length; j++){
                    Object value = sd.getFirstValue(fields[j]);
                    if(value instanceof Date) {
                        values[j] = value == null ? "" : org.apache.commons.lang.time.DateFormatUtils.format((Date)value, "yyyy-MM-dd");
                    } else {
                        values[j] = value == null ? "" : value.toString();
                    }
                }

                //now handle the assertions
                java.util.Collection<Object> assertions = sd.getFieldValues("assertions");

                //Handle the case where there a no assertions against a record
                if(assertions == null){
                    assertions = Collections.EMPTY_LIST;
                }

                for(int k = 0; k < qaFields.length; k++){
                    values[fields.length + k] = Boolean.toString(assertions.contains(qaFields[k]));
                }

                rw.write(values);

                //increment the counters....
                incrementCount(uidStats, sd.getFieldValue("institution_uid"));
                incrementCount(uidStats, sd.getFieldValue("collection_uid"));
                incrementCount(uidStats, sd.getFieldValue("data_provider_uid"));
                incrementCount(uidStats,  sd.getFieldValue("data_resource_uid"));
            }
        }
        dd.updateCounts(count);
        return count;
    }

    /**
     * Note - this method extracts from CASSANDRA rather than the Index.
     */
    public ConcurrentMap<String, AtomicInteger> writeResultsToStream(
            DownloadRequestParams downloadParams, OutputStream out, int i,
            boolean includeSensitive, DownloadDetailsDTO dd, boolean limit) throws Exception {

        int resultsCount = 0;
        ConcurrentMap<String, AtomicInteger> uidStats = new ConcurrentHashMap<>();
        //stores the remaining limit for data resources that have a download limit
        Map<String, Integer> downloadLimit = new HashMap<>();

        try {
            SolrQuery solrQuery = initSolrQuery(downloadParams,false,null);
            //ensure that the qa facet is being ordered alphabetically so that the order is consistent.
            boolean getAssertionsFromFacets = "all".equals(downloadParams.getQa()) || "includeall".equals(downloadParams.getQa());
            if(getAssertionsFromFacets){
                //set the order for the facet to be based on the index - this will force the assertions to be returned in the same order each time
                //based on alphabetical sort.  The number of QA's may change between searches so we can't guarantee that the order won't change
                solrQuery.add("f.assertions.facet.sort","index");
            }
            formatSearchQuery(downloadParams);
            //add context information
            updateQueryContext(downloadParams);
            logger.info("search query: " + downloadParams.getFormattedQuery());

            solrQuery.setQuery(buildSpatialQueryString(downloadParams));
            //Only the fields specified below will be included in the results from the SOLR Query
            solrQuery.setFields("row_key", "institution_uid", "collection_uid", "data_resource_uid", "data_provider_uid");

            String dFields = downloadParams.getFields();

            if(includeSensitive){
                //include raw latitude and longitudes
                dFields = dFields.replaceFirst("decimalLatitude.p", "decimalLatitude,decimalLongitude,decimalLatitude.p").replaceFirst(",locality,", ",locality,sensitive_locality,");
            }

            StringBuilder  sb = new StringBuilder(dFields);
            if(downloadParams.getExtra().length()>0) {
                sb.append(",").append(downloadParams.getExtra());
            }
            StringBuilder qasb = new StringBuilder();

            QueryResponse qr = runSolrQuery(solrQuery, downloadParams.getFq(), 0, 0, "_docid_", "asc");
            dd.setTotalRecords(qr.getResults().getNumFound());
            //get the assertion facets to add them to the download fields
            List<FacetField> facets = qr.getFacetFields();
            for (FacetField facet : facets) {
                if (facet.getName().equals("assertions") && facet.getValueCount() > 0) {
                    qasb.append(getQAFromFacet(facet));
                } else if (facet.getName().equals("data_resource_uid") && checkDownloadLimits) {
                    //populate the download limit
                    initDownloadLimits(downloadLimit, facet);
                }
            }

            if ("includeall".equals(downloadParams.getQa())) {
                qasb = getAllQAFields();
            }

            //Write the header line
            String qas = qasb.toString();

            String[] fields = sb.toString().split(",");
            String[] qaFields = qas.equals("")?new String[]{}:qas.split(",");
            String[] qaTitles = downloadFields.getHeader(qaFields,false,false);
            String[] titles = downloadFields.getHeader(fields,true,downloadParams.getDwcHeaders());
            //append sensitive fields for the header only
            if (!includeSensitive && dd.getSensitiveFq() != null) {
                //sensitive headers do not have a DwC name, always set getIndexFields dwcHeader=false
                List<String>[] sensitiveHdr = downloadFields.getIndexFields(sensitiveSOLRHdr, false);

                titles = org.apache.commons.lang3.ArrayUtils.addAll(titles, sensitiveHdr[2].toArray(new String[]{}));
            }
            String[] header = org.apache.commons.lang3.ArrayUtils.addAll(titles, qaTitles);
            //Create the Writer that will be used to format the records
            //construct correct RecordWriter based on the supplied fileType
            final au.org.ala.biocache.RecordWriter rw = downloadParams.getFileType().equals("csv") ?
                    new CSVRecordWriter(out, header, downloadParams.getSep(), downloadParams.getEsc()) :
                    (downloadParams.getFileType().equals("tsv") ?
                        new TSVRecordWriter(out, header) :
                        new ShapeFileRecordWriter(tmpShapefileDir, downloadParams.getFile(), out, (String[]) ArrayUtils.addAll(fields, qaFields)));

            try {
                if(rw instanceof ShapeFileRecordWriter) {
                    dd.setHeaderMap(((ShapeFileRecordWriter)rw).getHeaderMappings());
                }

                //retain output header fields and field names for inclusion of header info in the download
                StringBuilder infoFields = new StringBuilder("infoFields,");
                for (String h : fields) infoFields.append(",").append(h);
                for (String h : qaFields) infoFields.append(",").append(h);

                StringBuilder infoHeader = new StringBuilder("infoHeaders,");
                for (String h : header) infoHeader.append(",").append(h);

                uidStats.put(infoFields.toString(), new AtomicInteger(-1));
                uidStats.put(infoHeader.toString(), new AtomicInteger(-2));

                //download the records that have limits first...
                if(downloadLimit.size() > 0) {
                    String[] originalFq = downloadParams.getFq();
                    StringBuilder fqBuilder = new StringBuilder("-(");
                    for(String dr : downloadLimit.keySet()){
                        //add another fq to the search for data_resource_uid
                         downloadParams.setFq((String[])ArrayUtils.add(originalFq, "data_resource_uid:" + dr));
                         resultsCount = downloadRecords(downloadParams, rw, downloadLimit, uidStats, fields, qaFields,
                                 resultsCount, dr, includeSensitive,dd,limit);
                         if(fqBuilder.length() > 2) {
                             fqBuilder.append(" OR ");
                         }
                         fqBuilder.append("data_resource_uid:").append(dr);
                    }
                    fqBuilder.append(")");
                    //now include the rest of the data resources
                    //add extra fq for the remaining records
                    downloadParams.setFq((String[])ArrayUtils.add(originalFq, fqBuilder.toString()));
                    resultsCount = downloadRecords(downloadParams, rw, downloadLimit, uidStats, fields, qaFields,
                            resultsCount, null, includeSensitive,dd,limit);
                } else {
                    //download all at once
                    downloadRecords(downloadParams, rw, downloadLimit, uidStats, fields, qaFields, resultsCount,
                            null, includeSensitive,dd,limit);
                }
            } finally {
                rw.finalise();
            }
        } catch (SolrServerException ex) {
            logger.error("Problem communicating with SOLR server. " + ex.getMessage(), ex);
        }

        return uidStats;
    }

    private String getQAFromFacet(FacetField facet) {
        StringBuilder qasb = new StringBuilder();
        for (FacetField.Count facetEntry : facet.getValues()) {
            if (facetEntry.getCount() > 0) {
                if (qasb.length() > 0) {
                    qasb.append(",");
                }
                if (facetEntry.getName() != null) {
                    qasb.append(facetEntry.getName());
                }
            }
        }
        return qasb.toString();
    }

    /**
     * Downloads the records for the supplied query. Used to break up the download into components
     * 1) 1 call for each data resource that has a download limit (supply the data resource uid as the argument dataResource)
     * 2) 1 call for the remaining records
     * @param downloadParams
     * @param downloadLimit
     * @param uidStats
     * @param fields
     * @param qaFields
     * @param resultsCount
     * @param dataResource The dataResource being download.  This should be null if multiple data resource are being downloaded.
     * @return
     * @throws Exception
     */
    private int downloadRecords(DownloadRequestParams downloadParams, au.org.ala.biocache.RecordWriter writer,
                Map<String, Integer> downloadLimit,  ConcurrentMap<String, AtomicInteger> uidStats,
                String[] fields, String[] qaFields,int resultsCount, String dataResource, boolean includeSensitive,
                DownloadDetailsDTO dd, boolean limit) throws Exception {
        logger.info("download query: " + downloadParams.getQ());

        SolrQuery solrQuery = initSolrQuery(downloadParams,false,null);
        solrQuery.setRows(limit ? MAX_DOWNLOAD_SIZE : -1);
        formatSearchQuery(downloadParams);
        solrQuery.setQuery(buildSpatialQueryString(downloadParams));
        // Only the fields specified below will be included in the results from the SOLR Query
        solrQuery.setFields("row_key", "institution_uid", "collection_uid", "data_resource_uid", "data_provider_uid");

        int pageSize = downloadBatchSize;
        StringBuilder  sb = new StringBuilder(downloadParams.getFields());
        if(downloadParams.getExtra().length() > 0) {
            sb.append(",").append(downloadParams.getExtra());
        }

        List<SolrQuery> queries = new ArrayList<SolrQuery>();
        queries.add(solrQuery);

        //split into sensitive and non-sensitive queries when
        // - not including all sensitive values
        // - there is a sensitive fq
        List<SolrQuery> sensitiveQ = new ArrayList<SolrQuery>();
        if (!includeSensitive && dd.getSensitiveFq() != null) {
            sensitiveQ = splitQueries(queries, dd.getSensitiveFq(), null, null);
        }

        final String [] sensitiveFields;
        final String [] notSensitiveFields;
        if (!includeSensitive && dd.getSensitiveFq() != null) {
            //lookup for fields from sensitive queries
            sensitiveFields = org.apache.commons.lang3.ArrayUtils.addAll(fields, sensitiveCassandraHdr);

            //use general fields when sensitive data is not permitted
            notSensitiveFields = org.apache.commons.lang3.ArrayUtils.addAll(fields, notSensitiveCassandraHdr);
        } else {
            sensitiveFields = new String[0];
            notSensitiveFields = fields;
        }

        for (SolrQuery q: queries) {
            int startIndex = 0;

            String [] fq = downloadParams.getFq();
            if (q.getFilterQueries() != null && q.getFilterQueries().length > 0) {
                if (fq == null) {
                    fq = new String[0];
                }
                fq = org.apache.commons.lang3.ArrayUtils.addAll(fq, q.getFilterQueries());
            }

            QueryResponse qr = runSolrQuery(q, fq, pageSize, startIndex, "_docid_", "asc");
            List<String> uuids = new ArrayList<String>();

            while (qr.getResults().size() > 0 && (!limit || resultsCount < MAX_DOWNLOAD_SIZE) && shouldDownload(dataResource, downloadLimit, false)) {
                logger.debug("Start index: " + startIndex);

                //cycle through the results adding them to the list that will be sent to cassandra
                for (SolrDocument sd : qr.getResults()) {
                    if (sd.getFieldValue("data_resource_uid") != null) {
                        String druid = sd.getFieldValue("data_resource_uid").toString();
                        if (shouldDownload(druid, downloadLimit, true) && (!limit || resultsCount < MAX_DOWNLOAD_SIZE)) {
                            resultsCount++;
                            uuids.add(sd.getFieldValue("row_key").toString());

                            //increment the counters....
                            incrementCount(uidStats, sd.getFieldValue("institution_uid"));
                            incrementCount(uidStats, sd.getFieldValue("collection_uid"));
                            incrementCount(uidStats, sd.getFieldValue("data_provider_uid"));
                            incrementCount(uidStats, druid);
                        }
                    }
                }

                String[] newMiscFields;
                if (sensitiveQ.contains(q)) {
                    newMiscFields = au.org.ala.biocache.Store.writeToWriter(writer, uuids.toArray(new String[]{}), sensitiveFields, qaFields, true, (dd.getRequestParams() != null ? dd.getRequestParams().getIncludeMisc() : false), dd.getMiscFields());
                } else {
                    newMiscFields = au.org.ala.biocache.Store.writeToWriter(writer, uuids.toArray(new String[]{}), notSensitiveFields, qaFields, includeSensitive, (dd.getRequestParams() != null ? dd.getRequestParams().getIncludeMisc() : false), dd.getMiscFields());
                }
                dd.setMiscFields(newMiscFields);
                startIndex += pageSize;
                uuids.clear();
                dd.updateCounts(qr.getResults().size());
                if (!limit || resultsCount < MAX_DOWNLOAD_SIZE) {
                    //we have already set the Filter query the first time the query was constructed rerun with he same params but different startIndex
                    qr = runSolrQuery(q, null, pageSize, startIndex, "_docid_", "asc");
                }
            }
        }
        return resultsCount;
    }

    /**
     * Split a list of queries by a fq.
     *
     */
    private List<SolrQuery> splitQueries(List<SolrQuery> queries, String fq, String [] fqFields, String [] notFqFields) {
        List<SolrQuery> notFQ = new ArrayList<SolrQuery>();
        List<SolrQuery> fQ = new ArrayList<SolrQuery>();

        for (SolrQuery query : queries) {
            SolrQuery nsq = query.getCopy().addFilterQuery("-(" + fq + ")");
            if (notFqFields != null) {
                for (String field : notFqFields) nsq.addField(field);
            }
            notFQ.add(nsq);

            SolrQuery sq = query.getCopy().addFilterQuery(fq);
            if (fqFields != null) {
                for (String field : fqFields) sq.addField(field);
            }
            fQ.add(sq);
        }

        queries.clear();
        queries.addAll(notFQ);
        queries.addAll(fQ);

        return fQ;
    }

    /**
     * Indicates whether or not a records from the supplied data resource should be included
     * in the download. (based on download limits)
     * @param druid
     * @param limits
     * @param decrease whether or not to decrease the download limit available
     */
    private boolean shouldDownload(String druid, Map<String, Integer>limits, boolean decrease){
        if(checkDownloadLimits){
            if(!limits.isEmpty() && limits.containsKey(druid)){
                Integer remainingLimit = limits.get(druid);
                if(remainingLimit == 0){
                    return false;
                }
                if(decrease){
                    limits.put(druid, remainingLimit-1);
                }
            }
        }
        return true;
    }

    /**
     * Initialises the download limit tracking
     * @param map
     * @param facet
     */
    private void initDownloadLimits(Map<String,Integer> map,FacetField facet){
        //get the download limits from the cache
        Map<String, Integer>limits = collectionCache.getDownloadLimits();
        for(FacetField.Count facetEntry :facet.getValues()){
            String name = facetEntry.getName() != null ? facetEntry.getName() : "";
            Integer limit = limits.get(name);
            if(limit != null && limit >0){
                //check to see if the number of records returned from the query execeeds the limit
                if(limit < facetEntry.getCount())
                    map.put(name, limit);
            }
        }
        if(map.size()>0) {
            logger.debug("Downloading with the following limits: " + map);
        }
    }

    private static void incrementCount(ConcurrentMap<String, AtomicInteger> values, Object uid) {
        if (uid != null) {
            String nextKey = uid.toString();
            // TODO: When bumping to Java-8 this can use computeIfAbsent to avoid all unnecessary object creation and putIfAbsent
            if(values.containsKey(nextKey)) {
                values.get(nextKey).incrementAndGet();
            }
            else {
                // This checks whether another thread inserted the count first
                AtomicInteger putIfAbsent = values.putIfAbsent(nextKey, new AtomicInteger(1));
                if(putIfAbsent != null) {
                    // Another thread inserted first, increment its counter instead
                    putIfAbsent.incrementAndGet();
                }
            }
        }
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#getFacetPoints(au.org.ala.biocache.dto.SpatialSearchRequestParams, au.org.ala.biocache.dto.PointType)
     */
    @Override
    public List<OccurrencePoint> getFacetPoints(SpatialSearchRequestParams searchParams, PointType pointType) throws Exception {
        return getPoints(searchParams, pointType, -1);
    }

    private List<OccurrencePoint> getPoints(SpatialSearchRequestParams searchParams, PointType pointType, int max) throws Exception {
        List<OccurrencePoint> points = new ArrayList<OccurrencePoint>(); // new OccurrencePoint(PointType.POINT);
        formatSearchQuery(searchParams);
        logger.info("search query: " + searchParams.getFormattedQuery());

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setQuery(buildSpatialQueryString(searchParams));
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField(pointType.getLabel());
        solrQuery.setFacetMinCount(1);
        solrQuery.setFacetLimit(max);  // unlimited = -1

        //add the context information
        updateQueryContext(searchParams);

        QueryResponse qr = runSolrQuery(solrQuery, searchParams.getFq(), 1, 0, "score", "asc");
        List<FacetField> facets = qr.getFacetFields();

        if (facets != null) {
            for (FacetField facet : facets) {
                List<FacetField.Count> facetEntries = facet.getValues();
                if (facet.getName().contains(pointType.getLabel()) && (facetEntries != null) && (facetEntries.size() > 0)) {

                    for (FacetField.Count fcount : facetEntries) {
                        if (StringUtils.isNotEmpty(fcount.getName()) && fcount.getCount() > 0) {
                            OccurrencePoint point = new OccurrencePoint(pointType);
                            point.setCount(fcount.getCount());
                            String[] pointsDelimited = StringUtils.split(fcount.getName(), ',');
                            List<Float> coords = new ArrayList<Float>();

                            for (String coord : pointsDelimited) {
                                try {
                                    Float decimalCoord = Float.parseFloat(coord);
                                    coords.add(decimalCoord);
                                } catch (NumberFormatException numberFormatException) {
                                    logger.warn("Error parsing Float for Lat/Long: " + numberFormatException.getMessage(), numberFormatException);
                                }
                            }

                            if (!coords.isEmpty()) {
                                Collections.reverse(coords); // must be long, lat order
                                point.setCoordinates(coords);
                                points.add(point);
                            }
                        }
                    }
                }
            }
        }
        return points;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#getFacetPointsShort(au.org.ala.biocache.dto.SpatialSearchRequestParams, String)
     */
    @Override
    public FacetField getFacetPointsShort(SpatialSearchRequestParams searchParams, String pointType) throws Exception {
        formatSearchQuery(searchParams);
        logger.info("search query: " + searchParams.getFormattedQuery());

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setRequestHandler("standard");
        solrQuery.setQuery(buildSpatialQueryString(searchParams));
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField(pointType);
        solrQuery.setFacetMinCount(1);
        solrQuery.setFacetLimit(searchParams.getFlimit());//MAX_DOWNLOAD_SIZE);  // unlimited = -1

        //add the context information
        updateQueryContext(searchParams);

        QueryResponse qr = runSolrQuery(solrQuery, searchParams.getFq(), 0, 0, "_docid_", "asc");
        List<FacetField> facets = qr.getFacetFields();

        //return first facet, there should only be 1
        if (facets != null && facets.size() > 0) {
            return facets.get(0);
        }
        return null;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#getOccurrences(au.org.ala.biocache.dto.SpatialSearchRequestParams, au.org.ala.biocache.dto.PointType, String, int)
     */
    @Override
    public List<OccurrencePoint> getOccurrences(SpatialSearchRequestParams searchParams, PointType pointType, String colourBy, int searchType) throws Exception {

        List<OccurrencePoint> points = new ArrayList<OccurrencePoint>();
        searchParams.setPageSize(100);

        String queryString = "";
        formatSearchQuery(searchParams);
        if (searchType == 0) {
            queryString = searchParams.getFormattedQuery();
        } else if (searchType == 1) {
            queryString = buildSpatialQueryString(searchParams.getFormattedQuery(), searchParams.getLat(), searchParams.getLon(), searchParams.getRadius());
        }

        logger.info("search query: " + queryString);

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setQuery(queryString);
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField(pointType.getLabel());
        solrQuery.setFacetMinCount(1);
        solrQuery.setFacetLimit(MAX_DOWNLOAD_SIZE);  // unlimited = -1

        //add the context information
        updateQueryContext(searchParams);

        QueryResponse qr = runSolrQuery(solrQuery, searchParams);
        SearchResultDTO searchResults = processSolrResponse(searchParams, qr, solrQuery, OccurrenceIndex.class);
        List<OccurrenceIndex> ocs = searchResults.getOccurrences();

        if (!ocs.isEmpty() && ocs.size() > 0) {

            for (OccurrenceIndex oc : ocs) {

                List<Float> coords = new ArrayList<Float>();
                coords.add(oc.getDecimalLongitude().floatValue());
                coords.add(oc.getDecimalLatitude().floatValue());

                OccurrencePoint point = new OccurrencePoint();
                point.setCoordinates(coords);

                if (searchType == 0) {
                    // for now, let's set the colour in this one.

                    String value = "Not available";
                    if (StringUtils.isNotBlank(colourBy)) {

                        try {
                            if(oc != null){
                                java.util.Map map = oc.toMap();
                                if (map != null) {
                                    //check to see if it is empty otherwise a NPE is thrown when option.get is called
                                    if (map.containsKey(colourBy)) {
                                        value = (String) map.get(colourBy);
                                    }
                                    point.setOccurrenceUid(value);
                                }
                            }
                        } catch (Exception e) {
                            logger.debug(e.getMessage(), e);
                        }
                    }

                } else if (searchType == 1) {
                    point.setOccurrenceUid(oc.getUuid());
                }

                points.add(point);
            }
        }

        return points;
    }

    /**
     * http://ala-biocache1.vm.csiro.au:8080/solr/select?q=*:*&rows=0&facet=true&facet.field=data_provider_id&facet.field=data_provider&facet.sort=data_provider_id
     *
     * @see au.org.ala.biocache.dao.SearchDAO#getDataProviderCounts()
     */
    //IS THIS BEING USED BY ANYTHING??
    @Override
    public List<DataProviderCountDTO> getDataProviderCounts() throws Exception {

        List<DataProviderCountDTO> dpDTOs = new ArrayList<DataProviderCountDTO>(); // new OccurrencePoint(PointType.POINT);
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setQuery("*:*");
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField("data_provider_uid");
        solrQuery.addFacetField("data_provider");
        solrQuery.setFacetMinCount(1);
        QueryResponse qr = runSolrQuery(solrQuery, null, 1, 0, "data_provider", "asc");
        List<FacetField> facets = qr.getFacetFields();

        if (facets != null && facets.size() == 2) {

            FacetField dataProviderIdFacet = facets.get(0);
            FacetField dataProviderNameFacet = facets.get(1);

            List<FacetField.Count> dpIdEntries = dataProviderIdFacet.getValues();
            List<FacetField.Count> dpNameEntries = dataProviderNameFacet.getValues();

            if(dpIdEntries != null){
                for (int i = 0; i < dpIdEntries.size(); i++) {

                    FacetField.Count dpIdEntry = dpIdEntries.get(i);
                    FacetField.Count dpNameEntry = dpNameEntries.get(i);

                    String dataProviderId = dpIdEntry.getName();
                    String dataProviderName = dpNameEntry.getName();
                    long count = dpIdEntry.getCount();

                    if (count > 0) {
                        DataProviderCountDTO dto = new DataProviderCountDTO(dataProviderId, dataProviderName, count);
                        dpDTOs.add(dto);
                    }
                }
            }
        }
        logger.info("Find data providers = " + dpDTOs.size());

        return dpDTOs;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#findRecordsForLocation(au.org.ala.biocache.dto.SpatialSearchRequestParams, au.org.ala.biocache.dto.PointType)
     * This is used by explore your area
     */
    @Override
    public List<OccurrencePoint> findRecordsForLocation(SpatialSearchRequestParams requestParams, PointType pointType) throws Exception {
        return getPoints(requestParams, pointType, MAX_DOWNLOAD_SIZE);
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#findAllSpeciesByCircleAreaAndHigherTaxa(au.org.ala.biocache.dto.SpatialSearchRequestParams, String)
     */
    @Override
    public List<TaxaCountDTO> findAllSpeciesByCircleAreaAndHigherTaxa(SpatialSearchRequestParams requestParams, String speciesGroup) throws Exception {
        //add the context information
        updateQueryContext(requestParams);
        // format query so lsid searches are properly escaped, etc
        formatSearchQuery(requestParams);
        String queryString = buildSpatialQueryString(requestParams);
        logger.debug("The species count query " + queryString);

        List<String> fqList = new ArrayList<String>();
        //only add the FQ's if they are not the default values
        if(requestParams.getFq().length>0 && (requestParams.getFq()[0]).length()>0){
            org.apache.commons.collections.CollectionUtils.addAll(fqList, requestParams.getFq());
        }
        List<TaxaCountDTO> speciesWithCounts = getSpeciesCounts(queryString,
                                                                fqList,
                                                                CollectionUtils.arrayToList(requestParams.getFacets()),
                                                                requestParams.getPageSize(),
                                                                requestParams.getStart(),
                                                                requestParams.getSort(),
                                                                requestParams.getDir());

        return speciesWithCounts;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#findRecordByStateFor(java.lang.String)
     * IS THIS BEGIN USED OR NECESSARY
     */
    @Override
    public List<FieldResultDTO> findRecordByStateFor(String query)
            throws Exception {
        List<FieldResultDTO> fDTOs = new ArrayList<FieldResultDTO>(); // new OccurrencePoint(PointType.POINT);
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setQuery(query);
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField("state");
        solrQuery.setFacetMinCount(1);
        QueryResponse qr = runSolrQuery(solrQuery, null, 1, 0, "data_provider", "asc");
        List<FacetField> facets = qr.getFacetFields();
        FacetField ff = qr.getFacetField("state");
        if (ff != null) {
            for (Count count : ff.getValues()) {
                //only start adding counts when we hit a decade with some results.
                if (count.getCount() > 0) {
                    FieldResultDTO f = new FieldResultDTO(count.getName(), count.getCount());
                    fDTOs.add(f);
                }
            }
        }
        return fDTOs;
    }

    /**
     * Calculates the breakdown of the supplied query based on the supplied params
     */
    public TaxaRankCountDTO calculateBreakdown(BreakdownRequestParams queryParams) throws Exception {
        logger.debug("Attempting to find the counts for " + queryParams);

        TaxaRankCountDTO trDTO = null;
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        formatSearchQuery(queryParams);
        solrQuery.setQuery(buildSpatialQueryString(queryParams));
        queryParams.setPageSize(0);
        solrQuery.setFacet(true);
        solrQuery.setFacetMinCount(1);
        solrQuery.setFacetSort("count");
        solrQuery.setFacetLimit(-1);
        //add the context information
        updateQueryContext(queryParams);
        //add the rank:name as a fq if necessary
        if(StringUtils.isNotEmpty(queryParams.getName()) && StringUtils.isNotEmpty(queryParams.getRank())){
            queryParams.setFq((String[])ArrayUtils.addAll(queryParams.getFq(), new String[]{queryParams.getRank() +":" + queryParams.getName()}));
        }
        //add the ranks as facets
        if(queryParams.getLevel() == null){
            List<String> ranks = queryParams.getRank()!= null ? searchUtils.getNextRanks(queryParams.getRank(), queryParams.getName()==null) : searchUtils.getRanks();
            for (String r : ranks) {
                solrQuery.addFacetField(r);
            }
        } else {
            //the user has supplied the "exact" level at which to perform the breakdown
            solrQuery.addFacetField(queryParams.getLevel());
        }
        QueryResponse qr = runSolrQuery(solrQuery, queryParams);
        if(queryParams.getMax() != null && queryParams.getMax() >0){
            //need to get the return level that the number of facets are <=max ranks need to be processed in reverse order until max is satisfied
            if (qr.getResults().getNumFound() > 0) {
                List<FacetField> ffs =qr.getFacetFields();
                //reverse the facets so that they are returned in rank reverse order species, genus, family etc
                Collections.reverse(ffs);
                for(FacetField ff : ffs){
                    //logger.debug("Handling " + ff.getName());
                    trDTO = new TaxaRankCountDTO(ff.getName());
                    if (ff.getValues() != null && ff.getValues().size() <= queryParams.getMax()){
                        List<FieldResultDTO> fDTOs = new ArrayList<FieldResultDTO>();
                        for (Count count : ff.getValues()) {
                            if (count.getCount() > 0) {
                                FieldResultDTO f = new FieldResultDTO(count.getName(), count.getCount());
                                fDTOs.add(f);
                            }
                        }
                        trDTO.setTaxa(fDTOs);
                        break;
                    }
                }

            }
        } else if(queryParams.getRank() != null || queryParams.getLevel() != null){
            //just want to process normally the rank to facet on will start with the highest rank and then go down until one exists for
            if (qr.getResults().getNumFound() > 0) {
                List<FacetField> ffs =qr.getFacetFields();
                for (FacetField ff : ffs) {
                    trDTO = new TaxaRankCountDTO(ff.getName());
                    if (ff != null && ff.getValues() != null) {
                        List<Count> counts = ff.getValues();
                        if (counts.size() > 0) {
                            List<FieldResultDTO> fDTOs = new ArrayList<FieldResultDTO>();
                            for (Count count : counts) {
                                if (count.getCount() > 0) {
                                    FieldResultDTO f = new FieldResultDTO(count.getName(), count.getCount());
                                    fDTOs.add(f);
                                }
                            }
                            trDTO.setTaxa(fDTOs);
                            break;
                        }
                    }
                }
            }

        }
        return trDTO;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#findTaxonCountForUid(au.org.ala.biocache.dto.BreakdownRequestParams, String)
     * @deprecated use {@link #calculateBreakdown(BreakdownRequestParams)} instead
     */
    @Deprecated
    public TaxaRankCountDTO findTaxonCountForUid(BreakdownRequestParams breakdownParams,String query) throws Exception {
        TaxaRankCountDTO trDTO = null;
        List<String> ranks = breakdownParams.getLevel()== null ? searchUtils.getNextRanks(breakdownParams.getRank(), breakdownParams.getName()==null) : new ArrayList<String>();
        if(breakdownParams.getLevel() != null)
            ranks.add(breakdownParams.getLevel());
        if (ranks != null && ranks.size() > 0) {
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.setQueryType("standard");
            solrQuery.setQuery(query);
            solrQuery.setRows(0);
            solrQuery.setFacet(true);
            solrQuery.setFacetMinCount(1);
            solrQuery.setFacetSort("count");
            solrQuery.setFacetLimit(-1); //we want all facets
            for (String r : ranks) {
                solrQuery.addFacetField(r);
            }
            QueryResponse qr = runSolrQuery(solrQuery, getQueryContextAsArray(breakdownParams.getQc()), 1, 0, breakdownParams.getRank(), "asc");
            if (qr.getResults().size() > 0) {
                for (String r : ranks) {
                    trDTO = new TaxaRankCountDTO(r);
                    FacetField ff = qr.getFacetField(r);
                    if (ff != null && ff.getValues() != null) {
                        List<Count> counts = ff.getValues();
                        if (counts.size() > 0) {
                            List<FieldResultDTO> fDTOs = new ArrayList<FieldResultDTO>();
                            for (Count count : counts) {
                                FieldResultDTO f = new FieldResultDTO(count.getName(), count.getCount());
                                fDTOs.add(f);
                            }
                            trDTO.setTaxa(fDTOs);
                            break;
                        }
                    }
                }
            }
        }
        return trDTO;
    }

    /**
     * Convenience method for running solr query
     *
     * @param solrQuery
     * @param filterQuery
     * @param pageSize
     * @param startIndex
     * @param sortField
     * @param sortDirection
     * @return
     * @throws SolrServerException
     */
    private QueryResponse runSolrQuery(SolrQuery solrQuery, String filterQuery[], Integer pageSize,
            Integer startIndex, String sortField, String sortDirection) throws SolrServerException {
        SearchRequestParams requestParams = new SearchRequestParams();
        requestParams.setFq(filterQuery);
        requestParams.setPageSize(pageSize);
        requestParams.setStart(startIndex);
        requestParams.setSort(sortField);
        requestParams.setDir(sortDirection);
        return runSolrQuery(solrQuery, requestParams);
    }

    /**
     * Perform SOLR query - takes a SolrQuery and search params
     *
     * @param solrQuery
     * @param requestParams
     * @return
     * @throws SolrServerException
     */
    private QueryResponse runSolrQuery(SolrQuery solrQuery, SearchRequestParams requestParams) throws SolrServerException {

        if (requestParams.getFq() != null) {
            for (String fq : requestParams.getFq()) {
                // pull apart fq. E.g. Rank:species and then sanitize the string parts
                // so that special characters are escaped appropriately
                if (fq ==null || fq.isEmpty()) {
                    continue;
                }
                // use of AND/OR requires correctly formed fq.
                // Can overlap with values containing the same,
                // case sensitivity may help.
                if(fq.contains(" OR ") || fq.contains(" AND ") || fq.contains("Intersects(")) {
                    solrQuery.addFilterQuery(fq);
                    logger.info("adding filter query: " + fq);

                    continue;
                }
                String[] parts = fq.split(":", 2); // separate query field from query text
                if(parts.length>1){
                    logger.debug("fq split into: " + parts.length + " parts: " + parts[0] + " & " + parts[1]);

                    String prefix = null;
                    String suffix = null;
                    // don't escape range or '(multiple terms)' queries
                    if ((parts[1].contains("[") && parts[1].contains(" TO ") && parts[1].contains("]"))
                            || parts[0].startsWith("-(") || parts[0].startsWith("(")) {
                        prefix = parts[0];
                        suffix = parts[1];
                    } else {
                        if(parts[0].startsWith("-")) {
                            prefix = "-" + ClientUtils.escapeQueryChars(parts[0].substring(1));
                        } else {
                            prefix = ClientUtils.escapeQueryChars(parts[0]);
                        }
                        if(parts[1].equals("*")) {
                            suffix = parts[1];
                        } else {
                            boolean quoted = false;
                            StringBuffer sb = new StringBuffer();
                            if(parts[1].startsWith("\"") && parts[1].endsWith("\"")){
                                quoted = true;
                                parts[1] = parts[1].substring(1, parts[1].length()-1);
                                sb.append("\"");
                            }
                            sb.append(ClientUtils.escapeQueryChars(parts[1]));
                            if(quoted) sb.append("\"");
                            suffix = sb.toString();
                        }
                    }

                    // FIXME check for blank value and replace with constant
                    if(StringUtils.isEmpty(suffix)){
                        suffix = "Unknown";
                    }
                    solrQuery.addFilterQuery(prefix + ":" + suffix); // solrQuery.addFacetQuery(facetQuery)
                    logger.info("adding filter query: " + prefix + ":" + suffix);
                }
            }
        }

        // include null facets
        solrQuery.setFacetMissing(true);
        solrQuery.setRows(requestParams.getPageSize());
        solrQuery.setStart(requestParams.getStart());

        for(String sortField: requestParams.getSort().split(",")) {
            solrQuery.addSortField(sortField, ORDER.valueOf(requestParams.getDir()));
        }
        logger.debug("runSolrQuery: " + solrQuery.toString());

        QueryResponse qr = query(solrQuery, queryMethod); // can throw exception

        logger.debug("runSolrQuery: " + solrQuery.toString() + " qtime:" + qr.getQTime());

        return qr;
    }

    /**
     * Process the {@see org.apache.solr.client.solrj.response.QueryResponse} from a SOLR search and return
     * a {@link au.org.ala.biocache.dto.SearchResultDTO}
     *
     * @param qr
     * @param solrQuery
     * @return
     */
    private SearchResultDTO processSolrResponse(SearchRequestParams params, QueryResponse qr, SolrQuery solrQuery, Class resultClass) {
        SearchResultDTO searchResult = new SearchResultDTO();
        SolrDocumentList sdl = qr.getResults();
        // Iterator it = qr.getResults().iterator() // Use for download
        List<FacetField> facets = qr.getFacetFields();
        List<FacetField> facetDates = qr.getFacetDates();
        Map<String, Integer> facetQueries = qr.getFacetQuery();
        if (facetDates != null) {
            logger.debug("Facet dates size: " + facetDates.size());
            facets.addAll(facetDates);
        }

        List<OccurrenceIndex> results = qr.getBeans(resultClass);

        //facet results
        searchResult.setTotalRecords(sdl.getNumFound());
        searchResult.setStartIndex(sdl.getStart());
        searchResult.setPageSize(solrQuery.getRows()); // pageSize
        searchResult.setStatus("OK");

        String solrSort = solrQuery.getSortField();
        String[] solrSorts = StringUtils.split(solrSort, ",");
        String sortDir = solrSorts[0].split(" ")[1]; // dir is assumed to be the same for all fields
        if(sortDir == "asc") {
            solrSort = solrSort.replaceAll(" asc", "");
        } else {
            solrSort = solrSort.replaceAll(" desc", "");
        }
        searchResult.setSort(solrSort);
        searchResult.setDir(sortDir);

        searchResult.setQuery(params.getUrlParams()); //this needs to be the original URL>>>>
        searchResult.setOccurrences(results);

        List<FacetResultDTO> facetResults = buildFacetResults(facets);

        //all belong to uncertainty range for now
        if(facetQueries != null && !facetQueries.isEmpty()) {
            Map<String, String> rangeMap = rangeBasedFacets.getRangeMap("uncertainty");
            List<FieldResultDTO> fqr = new ArrayList<FieldResultDTO>();
            for(String value: facetQueries.keySet()) {
                if(facetQueries.get(value)>0)
                    fqr.add(new FieldResultDTO(rangeMap.get(value), facetQueries.get(value),value));
            }
            facetResults.add(new FacetResultDTO("uncertainty", fqr));
        }

        //handle all the range based facets
        if(qr.getFacetRanges() != null){
            for(RangeFacet rfacet : qr.getFacetRanges()){
                List<FieldResultDTO> fqr = new ArrayList<FieldResultDTO>();
                if(rfacet instanceof Numeric){
                    Numeric nrfacet = (Numeric) rfacet;
                    List<RangeFacet.Count> counts = nrfacet.getCounts();
                    //handle the before
                    if(nrfacet.getBefore().intValue()>0){
                      fqr.add(new FieldResultDTO("[* TO " + getUpperRange(nrfacet.getStart().toString(), nrfacet.getGap(),false) + "]",
                              nrfacet.getBefore().intValue()));
                    }
                    for(RangeFacet.Count count:counts){
                        String title = getRangeValue(count.getValue(), nrfacet.getGap());
                        fqr.add(new FieldResultDTO(title,count.getCount()));
                    }
                    //handle the after
                    if(nrfacet.getAfter().intValue()>0){
                      fqr.add(new FieldResultDTO("["+nrfacet.getEnd().toString()+" TO *]",nrfacet.getAfter().intValue()));
                    }
                    facetResults.add(new FacetResultDTO(nrfacet.getName(), fqr));
                }
            }
        }

        //update image URLs
        for(OccurrenceIndex oi : results){
            updateImageUrls(oi);
        }

        searchResult.setFacetResults(facetResults);
        // The query result is stored in its original format so that all the information
        // returned is available later on if needed

        searchResult.setQr(qr);
        return searchResult;
    }

    /**
     * Build the facet results.
     *
     * @param facets
     * @return
     */
    private List<FacetResultDTO> buildFacetResults(List<FacetField> facets) {
        List<FacetResultDTO> facetResults = new ArrayList<FacetResultDTO>();
        // populate SOLR facet results
        if (facets != null) {
            for (FacetField facet : facets) {
                List<Count> facetEntries = facet.getValues();
                if ((facetEntries != null) && (facetEntries.size() > 0)) {
                    ArrayList<FieldResultDTO> r = new ArrayList<FieldResultDTO>();
                    for (Count fcount : facetEntries) {
                        //check to see if the facet field is an uid value that needs substitution
                        if (fcount.getCount() == 0) continue;
                        if (fcount.getName() == null) {
                            r.add(new FieldResultDTO("", fcount.getCount(), "-" + facet.getName() + ":*"));
                        } else {
                            if(fcount.getName().equals(DECADE_PRE_1850_LABEL)){
                                r.add(0, new FieldResultDTO(
                                        getFacetValueDisplayName(facet.getName(), fcount.getName()),
                                        fcount.getCount(),
                                        getFormattedFqQuery(facet.getName(), fcount.getName())
                                ));
                            } else {
                                r.add(new FieldResultDTO(
                                        getFacetValueDisplayName(facet.getName(), fcount.getName()),
                                        fcount.getCount(),
                                        getFormattedFqQuery(facet.getName(), fcount.getName())
                                ));
                            }
                        }
                    }
                    // only add facets if there are more than one facet result
                    if (r.size() > 0) {
                        FacetResultDTO fr = new FacetResultDTO(facet.getName(), r);
                        facetResults.add(fr);
                    }
                }
            }
        }
        return facetResults;
    }

    private void updateImageUrls(OccurrenceIndex oi){

        if(!StringUtils.isNotBlank(oi.getImage()))
            return;

        try {
            Map<String, String> formats = Config.mediaStore().getImageFormats(oi.getImage());
            oi.setImageUrl(formats.get("raw"));
            oi.setThumbnailUrl(formats.get("thumb"));
            oi.setSmallImageUrl(formats.get("small"));
            oi.setLargeImageUrl(formats.get("large"));
            String[] images = oi.getImages();
            if (images != null && images.length > 0) {
                String[] imageUrls = new String[images.length];
                for (int i = 0; i < images.length; i++) {
                    try {
                        Map<String, String> availableFormats = Config.mediaStore().getImageFormats(images[i]);
                        imageUrls[i] = availableFormats.get("large");
                    } catch (Exception ex) {
                        logger.warn("Unable to update image URL for " + images[i] + ": " + ex.getMessage());
                    }
                }
                oi.setImageUrls(imageUrls);
            }
        } catch (Exception ex) {
            logger.warn("Unable to update image URL for " + oi.getImage() + ": " + ex.getMessage());
        }
    }

    private String getRangeValue(String lower, Number gap){
        StringBuilder value=new StringBuilder("[");
        value.append(lower). append(" TO ").append(getUpperRange(lower,gap,true));
        return value.append("]").toString();
    }

    private String getUpperRange(String lower, Number gap, boolean addGap){
        if (gap instanceof Integer) {
          Integer upper = Integer.parseInt(lower) - 1;
          if(addGap)
              upper+= (Integer) gap;
          return upper.toString();
        } else if (gap instanceof Double) {
          BigDecimal upper = new BigDecimal(lower).add(new BigDecimal(-0.001));
          if(addGap) {
              upper = upper.add(new BigDecimal(gap.doubleValue()));
          }
          return upper.setScale(3, RoundingMode.HALF_UP).toString();
        } else {
          return lower;
        }
    }

    /**
     * Build the query string for a spatial query (using Spatial-Solr plugin syntax)
     *
     * TODO change param type to SearchRequestParams
     *
     * New plugin syntax
     * {!spatial circles=52.347,4.453,10}
     *
     * TODO different types of spatial queries...
     *
     * @param fullTextQuery
     * @param latitude
     * @param longitude
     * @param radius
     * @return
     */
    protected String buildSpatialQueryString(String fullTextQuery, Float latitude, Float longitude, Float radius) {
        StringBuilder sb= new StringBuilder();
        sb.append(spatialField).append(":\"Intersects(Circle(").append(longitude.toString());
        sb.append(" ").append(latitude.toString()).append(" d=").append(SpatialUtils.convertToDegrees(radius).toString());
        sb.append("))\"");
        if(StringUtils.isNotEmpty(fullTextQuery)){
          sb.append(" AND (").append(fullTextQuery).append(")");
        }
        return sb.toString();
    }

    protected String buildSpatialQueryString(SpatialSearchRequestParams searchParams){
        if(searchParams != null){
            StringBuilder sb = new StringBuilder();
            if(searchParams.getLat() != null){
                sb.append(spatialField).append(":\"Intersects(Circle(");
                sb.append(searchParams.getLon().toString()).append(" ").append(searchParams.getLat().toString());
                sb.append(" d=").append(SpatialUtils.convertToDegrees(searchParams.getRadius()).toString());
                sb.append("))\"");
            } else if(!StringUtils.isEmpty(searchParams.getWkt())){
                //format the wkt
                sb.append(SpatialUtils.getWKTQuery(spatialField, searchParams.getWkt(), false));
            }
            String query = StringUtils.isEmpty(searchParams.getFormattedQuery())? searchParams.getQ() : searchParams.getFormattedQuery();
            if(StringUtils.isNotEmpty(query)){
                if(sb.length()>0){
                    sb.append(" AND (");
                    sb.append(query).append(")");
                } else {
                    sb.append(query);
                }
            }
            return sb.toString();
        }
        return null;
    }

    protected void formatSearchQuery(SpatialSearchRequestParams searchParams) {
        formatSearchQuery(searchParams, false);
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#formatSearchQuery(SpatialSearchRequestParams, boolean)
     *
     * TODO Fix this to use a state.  REVISE!!
     *
     * @param searchParams
     */
    public void formatSearchQuery(SpatialSearchRequestParams searchParams, boolean forceQueryFormat) {
        // Only format the query if it doesn't already supply a formattedQuery.
        if(forceQueryFormat || StringUtils.isEmpty(searchParams.getFormattedQuery())){
            // set the query
            String query = searchParams.getQ();

            //cached query parameters are already formatted
            if(query.contains("qid:")) {
                Matcher matcher = qidPattern.matcher(query);
                long qidId = 0;
                while(matcher.find()) {
                    String value = matcher.group();
                    try {
                        String qidValue = SearchUtils.stripEscapedQuotes(value.substring(4));
                        qidId = Long.parseLong(qidValue);
                        Qid qid = qidCacheDao.get(qidValue);
                        if(qid != null) {
                            searchParams.setQId(qidId);
                            searchParams.setQ(qid.getQ());
                            //add the fqs from the params cache
                            if(qid.getFqs() != null){
                                String [] currentFqs = searchParams.getFq();
                                if(currentFqs == null || (currentFqs.length==1&&currentFqs[0].length()==0)){
                                    searchParams.setFq(qid.getFqs());
                                } else{
                                    //we need to add the current Fqs together
                                    searchParams.setFq((String[])ArrayUtils.addAll(currentFqs, qid.getFqs()));
                                }
                            }
                            String displayString = qid.getDisplayString();

                            if (StringUtils.isEmpty(searchParams.getWkt()) && StringUtils.isNotEmpty(qid.getWkt())) {
                                searchParams.setWkt(qid.getWkt());
                            } else if (StringUtils.isNotEmpty(searchParams.getWkt()) && StringUtils.isNotEmpty(qid.getWkt())) {
                                //Add the qid.wkt search term to searchParams.fq instead of wkt -> Geometry -> intersection -> wkt
                                String [] fq = new String[] { SpatialUtils.getWKTQuery(spatialField, qid.getWkt(), false) };
                                String [] currentFqs = searchParams.getFq();
                                if(currentFqs == null || (currentFqs.length==1&&currentFqs[0].length()==0)){
                                    searchParams.setFq(fq);
                                } else{
                                    //we need to add the current Fqs together
                                    searchParams.setFq((String[])ArrayUtils.addAll(currentFqs, fq));
                                }
                            }

                            if(StringUtils.isNotEmpty(searchParams.getWkt())){
                                displayString = displayString + " within user defined polygon" ;
                            }
                            searchParams.setDisplayString(displayString);

                            searchParams.setFormattedQuery(searchParams.getQ());
                            return;
                        }
                    } catch (NumberFormatException e) {
                    } catch (QidMissingException e) {
                    }
                }
            }
            StringBuffer queryString = new StringBuffer();
            StringBuffer displaySb = new StringBuffer();
            String displayString = query;

            // look for field:term sub queries and catch fields: matched_name & matched_name_children
            if (query.contains(":")) {
                // will match foo:bar, foo:"bar bash" & foo:bar\ bash
                Matcher matcher = termPattern.matcher(query);
                queryString.setLength(0);

                while (matcher.find()) {
                    String value = matcher.group();

                    logger.debug("term query: " + value );
                    logger.debug("groups: " + matcher.group(1) + "|" + matcher.group(2) );

                    if ("matched_name".equals(matcher.group(1))) {
                        // name -> accepted taxon name (taxon_name:)
                        String field = matcher.group(1);
                        String queryText = matcher.group(2);

                        if (queryText != null && !queryText.isEmpty()) {
                            String guid = speciesLookupService.getGuidForName(queryText.replaceAll("\"", "")); // strip any quotes
                            logger.info("GUID for " + queryText + " = " + guid);

                            if (guid != null && !guid.isEmpty()) {
                                String acceptedName = speciesLookupService.getAcceptedNameForGuid(guid); // strip any quotes
                                logger.info("acceptedName for " + queryText + " = " + acceptedName);

                                if (acceptedName != null && !acceptedName.isEmpty()) {
                                    field = "taxon_name";
                                    queryText = acceptedName;
                                }
                            } else {
                                field = "taxon_name";
                            }

                            // also change the display query
                            displayString = displayString.replaceAll("matched_name", "taxon_name");
                        }

                        if (StringUtils.containsAny(queryText, CHARS) && !queryText.startsWith("[")) {
                            // quote any text that has spaces or colons but not range queries
                            queryText = QUOTE + queryText + QUOTE;
                        }

                        logger.debug("queryText: " + queryText);

                        matcher.appendReplacement(queryString, matcher.quoteReplacement(field + ":" + queryText));

                    } else if ("matched_name_children".equals(matcher.group(1))) {
                        String field = matcher.group(1);
                        String queryText = matcher.group(2);

                        if (queryText != null && !queryText.isEmpty()) {
                            String guid = speciesLookupService.getGuidForName(queryText.replaceAll("\"", "")); // strip any quotes
                            logger.info("GUID for " + queryText + " = " + guid);

                            if (guid != null && !guid.isEmpty()) {
                                field = "lsid";
                                queryText = guid;
                            } else {
                                field = "taxon_name";
                            }
                        }

                        if (StringUtils.containsAny(queryText, CHARS) && !queryText.startsWith("[")) {
                            // quote any text that has spaces or colons but not range queries
                            queryText = QUOTE + queryText + QUOTE;
                        }

                        matcher.appendReplacement(queryString, Matcher.quoteReplacement(field + ":" + queryText));
                    } else {
                        matcher.appendReplacement(queryString, Matcher.quoteReplacement(value));
                    }
                }
                matcher.appendTail(queryString);
                query = queryString.toString();
            }

            // replace taxa queries with lsid: and text:
            if (query.contains("taxa:")) {
                Matcher matcher = taxaPattern.matcher(query);
                queryString.setLength(0);
                while (matcher.find()) {
                    String value = matcher.group();
                    String taxa = matcher.group(2);

                    logger.debug("found taxa " + taxa);

                    List<String> taxaQueries = new ArrayList<>();
                    taxaQueries.add(taxa);
                    List<String> guidsForTaxa = speciesLookupService.getGuidsForTaxa(taxaQueries);
                    String q = createQueryWithTaxaParam(taxaQueries, guidsForTaxa);

                    matcher.appendReplacement(queryString, q);
                }

                matcher.appendTail(queryString);

                query = queryString.toString();
                displayString = query;
            }

            //if the query string contains lsid: we will need to replace it with the corresponding lft range
            int last = 0;
            if (query.contains("lsid:")) {
                Matcher matcher = lsidPattern.matcher(query);
                queryString.setLength(0);
                while (matcher.find()) {
                    //only want to process the "lsid" if it does not represent taxon_concept_lsid etc...
                    if((matcher.start() >0 && query.charAt(matcher.start()-1) != '_') || matcher.start() == 0){
                    String value = matcher.group();
                    logger.debug("pre-processing " + value);

                    String lsid = matcher.group(2);
                    if (lsid.contains("\"")) {
                        //remove surrounding quotes, if present
                        lsid = lsid.replaceAll("\"","");
                    }
                    if (lsid.contains("\\")) {
                        //remove internal \ chars, if present
                        //noinspection MalformedRegex
                        lsid = lsid.replaceAll("\\\\","");
                    }
                    logger.debug("lsid = " + lsid);

                    String[] values = searchUtils.getTaxonSearch(lsid);
                        String lsidHeader = matcher.groupCount() > 1 && matcher.group(1).length() > 0 ? matcher.group(1) : "";
                    matcher.appendReplacement(queryString, lsidHeader +values[0]);
                    displaySb.append(query.substring(last, matcher.start()));
                    if(!values[1].startsWith("taxon_concept_lsid:")) {
                        displaySb.append(lsidHeader).append("<span class='lsid' id='").append(lsid).append("'>").append(values[1]).append("</span>");
                    } else {
                        displaySb.append(lsidHeader).append(values[1]);
                    }
                    last = matcher.end();
                    //matcher.appendReplacement(displayString, values[1]);
                    }
                }
                matcher.appendTail(queryString);
                displaySb.append(query.substring(last, query.length()));


                query = queryString.toString();
                displayString = displaySb.toString();
            }

            if (query.contains("urn")) {
                //escape the URN strings before escaping the rest this avoids the issue with attempting to search on a urn field
                Matcher matcher = urnPattern.matcher(query);
                queryString.setLength(0);
                while (matcher.find()) {
                    String value = matcher.group();

                    logger.debug("escaping lsid urns  " + value );

                    matcher.appendReplacement(queryString,prepareSolrStringForReplacement(value));
                }
                matcher.appendTail(queryString);
                query = queryString.toString();
            }
            if (query.contains("http")) {
                //escape the HTTP strings before escaping the rest this avoids the issue with attempting to search on a urn field
                Matcher matcher = httpPattern.matcher(query);
                queryString.setLength(0);
                while (matcher.find()) {
                    String value = matcher.group();
                    logger.debug("escaping lsid http uris  " + value );

                    matcher.appendReplacement(queryString,prepareSolrStringForReplacement(value));
                }
                matcher.appendTail(queryString);
                query = queryString.toString();
            }

            if(query.contains("Intersects")){
                Matcher matcher = spatialPattern.matcher(query);
                if(matcher.find()){
                    String spatial = matcher.group();
                    SpatialSearchRequestParams subQuery = new SpatialSearchRequestParams();
                    logger.debug("region Start : " + matcher.regionStart() + " start :  "+ matcher.start() + " spatial length " + spatial.length() + " query length " + query.length());

                    //format the search query of the remaining text only
                    subQuery.setQ(query.substring(matcher.start() + spatial.length(), query.length()));
                    //format the remaining query
                    formatSearchQuery(subQuery);

                    //now append Q's together
                    queryString.setLength(0);
                    //need to include the prefix
                    queryString.append(query.substring(0, matcher.start()));
                    queryString.append(spatial);
                    queryString.append(subQuery.getFormattedQuery());
                    searchParams.setFormattedQuery(queryString.toString());
                    //add the spatial information to the display string
                    if(spatial.contains("circles")){
                        String[] values = spatial.substring(spatial.indexOf("=") +1 , spatial.indexOf("}")).split(",");
                        if(values.length == 3){
                            displaySb.setLength(0);
                            displaySb.append(subQuery.getDisplayString());
                            displaySb.append(" - within ").append(values[2]).append(" km of point(")
                            .append(values[0]).append(",").append(values[1]).append(")");
                            searchParams.setDisplayString(displaySb.toString());
                        }

                    } else {
                        searchParams.setDisplayString(subQuery.getDisplayString() + " - within supplied region");
                    }
                }
            } else {
                //escape reserved characters unless the colon represnts a field name colon
                queryString.setLength(0);

                Matcher matcher = spacesPattern.matcher(query);
                while(matcher.find()){
                    String value = matcher.group();

                    //special cases to ignore from character escaping
                    //if the value is a single - or * it means that we don't want to escape it as it is likely
                    // to have occurred in the following situation -(occurrence_date:[* TO *]) or *:*
                    if(!value.equals("-") && /*!value.equals("*")  && !value.equals("*:*") && */ !value.endsWith("*")){

                        //split on the colon
                        String[] bits = StringUtils.split(value, ":", 2);
                        if(bits.length == 2){
                            if (!bits[0].contains("urn") && !bits[1].contains("urn\\") && !bits[0].contains("http") && !bits[1].contains("http\\"))
                                matcher.appendReplacement(queryString, bits[0] +":"+ prepareSolrStringForReplacement(bits[1]));

                        } else if(!value.endsWith(":")){
                            //need to ignore field names where the : is at the end because the pattern matching will
                            // return field_name: as a match when it has a double quoted value
                            //default behaviour is to escape all
                            matcher.appendReplacement(queryString, prepareSolrStringForReplacement(value));
                        }
                    }
                }
                matcher.appendTail(queryString);

                //substitute better display strings for collection/inst etc searches
                if(displayString.contains("_uid")){
                    displaySb.setLength(0);
                    String normalised = displayString.replaceAll("\"", "");
                    matcher = uidPattern.matcher(normalised);
                    while(matcher.find()){
                        String newVal = "<span>" + searchUtils.getUidDisplayString(matcher.group(1),matcher.group(2)) +"</span>";
                        if(newVal != null)
                            matcher.appendReplacement(displaySb, newVal);
                    }
                    matcher.appendTail(displaySb);
                    displayString = displaySb.toString();
                }
                if(searchParams.getQ().equals("*:*")){
                    displayString = "[all records]";
                }
                if(searchParams.getLat() != null && searchParams.getLon() != null && searchParams.getRadius() != null ){
                    displaySb.setLength(0);
                    displaySb.append(displayString);
                    displaySb.append(" - within ").append(searchParams.getRadius()).append(" km of point(")
                    .append(searchParams.getLat()).append(",").append(searchParams.getLon()).append(")");
                    displayString = displaySb.toString();

                }

                // substitute i18n version of field name, if found in messages.properties
                displayString = formatDisplayStringWithI18n(displayString);

                searchParams.setFormattedQuery(queryString.toString());
                logger.debug("formattedQuery = " + queryString);
                logger.debug("displayString = " + displayString);

                searchParams.setDisplayString(displayString);
            }

            //format the fq's for facets that need ranges substituted
            for(int i = 0; i < searchParams.getFq().length; i++){
                String fq = searchParams.getFq()[i];
                String[] parts = fq.split(":", 2);
                //check to see if the first part is a range based query and update if necessary
                Map<String, String> titleMap = rangeBasedFacets.getTitleMap(parts[0]);
                if(titleMap != null){
                    searchParams.getFq()[i]= titleMap.get(parts[1]);
                }
            }
        }
        searchParams.setDisplayString(formatDisplayStringWithI18n(searchParams.getDisplayString()));
    }

    /**
     * Substitute with i18n properties
     *
     * @param displayText
     * @return
     */
    public String formatDisplayStringWithI18n(String displayText){

        if(StringUtils.trimToNull(displayText) == null) return displayText;
        try {
            String formatted = displayText;

            Matcher m = indexFieldPatternMatcher.matcher(displayText);
            int currentPos = 0;
            while(m.find(currentPos)){
                String matchedIndexTerm = m.group(0).replaceAll(":","");
                MatchResult mr = m.toMatchResult();
                //if the matched term represents a layer lookup the title in the layers service
                Matcher lm = layersPattern.matcher(matchedIndexTerm);
                String i18n;
                if(lm.matches()){
                    i18n = layersService.getName(matchedIndexTerm);
                    if(i18n == null){
                        i18n = matchedIndexTerm;
                    }
                } else {
                    i18n = messageSource.getMessage("facet." + matchedIndexTerm, null, matchedIndexTerm, null);
                }
                logger.debug("i18n for " + matchedIndexTerm + " = " + i18n);
                if (!matchedIndexTerm.equals(i18n)) {

                  int nextWhitespace = displayText.substring(mr.end()).indexOf(" ");
                  String extractedValue = null;
                  if(nextWhitespace > 0){
                    extractedValue = displayText.substring(mr.end(), mr.end() + nextWhitespace);
                  } else {
                      //reached the end of the query
                    extractedValue = displayText.substring(mr.end());
                  }

                  String formattedExtractedValue = SearchUtils.stripEscapedQuotes(extractedValue);

                  String i18nForValue = messageSource.getMessage(matchedIndexTerm + "." + formattedExtractedValue, null, "", null);
                  if(i18nForValue.length() == 0) i18nForValue = messageSource.getMessage(formattedExtractedValue, null, "", null);

                  if(i18nForValue.length()>0){
                      formatted = formatted.replaceAll(matchedIndexTerm + ":"+ extractedValue, i18n + ":" + i18nForValue);
                  } else {
                      //just replace the matched index term
                      formatted = formatted.replaceAll(matchedIndexTerm,i18n);
                  }
                }
                currentPos = mr.end();
            }
            return formatted;

        } catch (Exception e){
            logger.debug(e.getMessage(), e);

            return displayText;
        }
    }

    /**
     * Creates a SOLR escaped string the can be used in a StringBuffer.appendReplacement
     * The appendReplacement needs an extra delimiting on the backslashes
     * @param value
     * @return
     */
    private String prepareSolrStringForReplacement(String value){
        //if starts and ends with quotes just escape the inside
        boolean quoted = false;
        StringBuffer sb = new StringBuffer();
        if(value.startsWith("\"") && value.endsWith("\"")){
            quoted = true;
            value = value.substring(1, value.length()-1);
            sb.append("\"");
        }
        sb.append(ClientUtils.escapeQueryChars(value).replaceAll("\\\\", "\\\\\\\\"));
        if(quoted) sb.append("\"");
        return sb.toString();
    }

    /**
     * Updates the supplied search params to cater for the query context
     * @param searchParams
     */
    protected void updateQueryContext(SearchRequestParams searchParams){
        //TODO better method of getting the mappings between qc on solr fields names
        String qc = searchParams.getQc();
        if(StringUtils.isNotEmpty(qc)){
            //add the query context to the filter query
            searchParams.setFq((String[])ArrayUtils.addAll(searchParams.getFq(), getQueryContextAsArray(qc)));
        }
    }

    protected String[] getQueryContextAsArray(String queryContext){
        if(StringUtils.isNotEmpty(queryContext)){
            String[] values = queryContext.split(",");
            for(int i =0; i<values.length; i++){
                String field = values[i];
                values[i]= field.replace("hub:", "data_hub_uid:");
            }
            //add the query context to the filter query
            return values;
        }
        return new String[]{};
    }

   protected void initDecadeBasedFacet(SolrQuery solrQuery,String field){
       solrQuery.add("facet.date", field);
       solrQuery.add("facet.date.start", DECADE_FACET_START_DATE); // facet date range starts from 1850
       solrQuery.add("facet.date.end", "NOW/DAY"); // facet date range ends for current date (gap period)
       solrQuery.add("facet.date.gap", "+10YEAR"); // gap interval of 10 years
       solrQuery.add("facet.date.other", DECADE_PRE_1850_LABEL); // include counts before the facet start date ("before" label)
       solrQuery.add("facet.date.include", "lower"); // counts will be included for dates on the starting date but not ending date
   }

    /**
     * Helper method to create SolrQuery object and add facet settings
     *
     * @return solrQuery the SolrQuery
     */
    protected SolrQuery initSolrQuery(SearchRequestParams searchParams, boolean substituteDefaultFacetOrder, Map<String,String[]> extraSolrParams) {

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        boolean rangeAdded = false;
        // Facets
        solrQuery.setFacet(searchParams.getFacet());
        if(searchParams.getFacet()) {
            for (String facet : searchParams.getFacets()) {
                if (facet.equals("date") || facet.equals("decade")) {
                    String fname = facet.equals("decade") ? OCCURRENCE_YEAR_INDEX_FIELD : "occurrence_" + facet;
                    initDecadeBasedFacet(solrQuery, fname);
                } else if(facet.equals("uncertainty")){
                    Map<String, String> rangeMap = rangeBasedFacets.getRangeMap("uncertainty");
                    for(String range: rangeMap.keySet()){
                        solrQuery.add("facet.query", range);
                    }
                } else if (facet.endsWith(RANGE_SUFFIX)){
                    //this facte need to have it ranges included.
                    if(!rangeAdded){
                        solrQuery.add("facet.range.other","before");
                        solrQuery.add("facet.range.other", "after");
                    }
                    String field = facet.replaceAll(RANGE_SUFFIX, "");
                    StatsIndexFieldDTO details = getRangeFieldDetails(field);
                    if(details != null){
                        solrQuery.addNumericRangeFacet(field, details.getStart(), details.getEnd(), details.getGap());
                    }
                } else {
                    solrQuery.addFacetField(facet);

                    if("".equals(searchParams.getFsort()) && substituteDefaultFacetOrder && FacetThemes.getFacetsMap().containsKey(facet)){
                      //now check if the sort order is different to supplied
                      String thisSort = FacetThemes.getFacetsMap().get(facet).getSort();
                      if(!searchParams.getFsort().equalsIgnoreCase(thisSort))
                          solrQuery.add("f." + facet + ".facet.sort", thisSort);
                    }

                }
            }

            solrQuery.setFacetMinCount(1);
            solrQuery.setFacetLimit(searchParams.getFlimit());
            //include this so that the default fsort is still obeyed.
            String fsort = "".equals(searchParams.getFsort()) ? "count" : searchParams.getFsort();
            solrQuery.setFacetSort(fsort);
            if(searchParams.getFoffset()>0)
              solrQuery.add("facet.offset", Integer.toString(searchParams.getFoffset()));
            if(StringUtils.isNotEmpty(searchParams.getFprefix()))
              solrQuery.add("facet.prefix", searchParams.getFprefix());
        }

        solrQuery.setRows(10);
        solrQuery.setStart(0);

        if (searchParams.getFl().length() > 0) {
            solrQuery.setFields(searchParams.getFl());
        }

        //add the extra SOLR params
        if(extraSolrParams != null){
            //automatically include the before and after params...
            if(!rangeAdded){
                solrQuery.add("facet.range.other","before");
                solrQuery.add("facet.range.other", "after");
            }
            for(String key : extraSolrParams.keySet()){
                String[] values = extraSolrParams.get(key);
                solrQuery.add(key, values);
            }
        }

        return solrQuery;
    }

    /**
     * Obtains the Statistics for the supplied field so it can be used to determine the ranges.
     * @param field
     * @return
     */
    private StatsIndexFieldDTO getRangeFieldDetails(String field){
        StatsIndexFieldDTO details = rangeFieldCache.get(field);
        Map<String, IndexFieldDTO> nextIndexFieldMap = indexFieldMap;
        if(details == null && nextIndexFieldMap != null){
            //get the details
            SpatialSearchRequestParams searchParams = new SpatialSearchRequestParams();
            searchParams.setQ("*:*");
            searchParams.setFacets(new String[]{field});
            try {
                Map<String, FieldStatsInfo> stats = getStatistics(searchParams);
                if(stats != null){
                    IndexFieldDTO ifdto = nextIndexFieldMap.get(field);
                    if(ifdto != null){
                        String type = ifdto.getDataType();
                        details = new StatsIndexFieldDTO(stats.get(field), type);
                        rangeFieldCache.put(field, details);
                    } else {
                        logger.debug("Unable to locate field:  " + field);

                        return null;
                    }
                }
            } catch(Exception e){
                logger.warn("Unable to obtain range from cache." ,e);
                details = null;
            }
        }

        return details;
    }

    /**
     * Get a distinct list of species and their counts using a facet search
     *
     * @param queryString
     * @param pageSize
     * @param sortField
     * @param sortDirection
     * @return
     * @throws SolrServerException
     */
    protected List<TaxaCountDTO> getSpeciesCounts(String queryString, List<String> filterQueries, List<String> facetFields, Integer pageSize,
            Integer startIndex, String sortField, String sortDirection) throws SolrServerException {

        List<TaxaCountDTO> speciesCounts = new ArrayList<TaxaCountDTO>();
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setQuery(queryString);

        if (filterQueries != null && filterQueries.size()>0) {
            //solrQuery.addFilterQuery("(" + StringUtils.join(filterQueries, " OR ") + ")");
            for (String fq : filterQueries) {
                solrQuery.addFilterQuery(fq);
            }
        }
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.setFacetSort(sortField);
        for (String facet : facetFields) {
            solrQuery.addFacetField(facet);
            logger.debug("adding facetField: " + facet);

        }
        //set the facet starting point based on the paging information
        solrQuery.setFacetMinCount(1);
        solrQuery.setFacetLimit(pageSize); // unlimited = -1 | pageSize
        solrQuery.add("facet.offset", Integer.toString(startIndex));
        logger.debug("getSpeciesCount query :" + solrQuery.getQuery());

        QueryResponse qr = runSolrQuery(solrQuery, null, 1, 0, "score", sortDirection);
        logger.info("SOLR query: " + solrQuery.getQuery() + "; total hits: " + qr.getResults().getNumFound());

        List<FacetField> facets = qr.getFacetFields();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\|");

        if (facets != null && facets.size() > 0) {
            logger.debug("Facets: " + facets.size() + "; facet #1: " + facets.get(0).getName());

            for (FacetField facet : facets) {
                List<FacetField.Count> facetEntries = facet.getValues();
                if ((facetEntries != null) && (facetEntries.size() > 0)) {

                    for(FacetField.Count fcount : facetEntries){
                        TaxaCountDTO tcDTO = null;
                        String name = fcount.getName() != null ? fcount.getName() : "";
                        if (fcount.getFacetField().getName().equals(NAMES_AND_LSID)) {
                            String[] values = p.split(name, 5);

                            if (values.length >= 5) {
                                if (!"||||".equals(name)) {
                                    tcDTO = new TaxaCountDTO(values[0], fcount.getCount());
                                    tcDTO.setGuid(StringUtils.trimToNull(values[1]));
                                    tcDTO.setCommonName(values[2]);
                                    tcDTO.setKingdom(values[3]);
                                    tcDTO.setFamily(values[4]);
                                    if(StringUtils.isNotEmpty(tcDTO.getGuid()))
                                        tcDTO.setRank(searchUtils.getTaxonSearch(tcDTO.getGuid())[1].split(":")[0]);
                                }
                            } else {
                                logger.debug("The values length: " + values.length + " :" + name);

                                tcDTO = new TaxaCountDTO(name, fcount.getCount());
                            }
                            //speciesCounts.add(i, tcDTO);
                            if(tcDTO != null && tcDTO.getCount() > 0)
                                speciesCounts.add(tcDTO);
                        }
                        else if(fcount.getFacetField().getName().equals(COMMON_NAME_AND_LSID)){
                            String[] values = p.split(name, 6);

                            if(values.length >= 5){
                                if (!"|||||".equals(name)) {
                                    tcDTO = new TaxaCountDTO(values[1], fcount.getCount());
                                    tcDTO.setGuid(StringUtils.trimToNull(values[2]));
                                    tcDTO.setCommonName(values[0]);
                                    //cater for the bug of extra vernacular name in the result
                                    tcDTO.setKingdom(values[values.length-2]);
                                    tcDTO.setFamily(values[values.length-1]);
                                    if(StringUtils.isNotEmpty(tcDTO.getGuid()))
                                        tcDTO.setRank(searchUtils.getTaxonSearch(tcDTO.getGuid())[1].split(":")[0]);
                                }
                            } else {
                                logger.debug("The values length: " + values.length + " :" + name);

                                tcDTO = new TaxaCountDTO(name, fcount.getCount());
                            }
                            //speciesCounts.add(i, tcDTO);
                            if(tcDTO != null && tcDTO.getCount() > 0){
                                speciesCounts.add(tcDTO);
                            }
                        }
                    }
                }
            }
        }

        return speciesCounts;
    }

    /**
     * Obtains a list and facet count of the source uids for the supplied query.
     *
     * @param searchParams
     * @return
     * @throws Exception
     */
    public Map<String, Integer> getSourcesForQuery(SpatialSearchRequestParams searchParams) throws Exception {

        Map<String, Integer> uidStats = new HashMap<String, Integer>();
        SolrQuery solrQuery = new SolrQuery();
        formatSearchQuery(searchParams);
        logger.info("The query : " + searchParams.getFormattedQuery());

        solrQuery.setQuery(buildSpatialQueryString(searchParams));
        solrQuery.setQueryType("standard");
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.setFacetMinCount(1);
        solrQuery.addFacetField("data_provider_uid");
        solrQuery.addFacetField("data_resource_uid");
        solrQuery.addFacetField("collection_uid");
        solrQuery.addFacetField("institution_uid");
        QueryResponse qr = runSolrQuery(solrQuery, searchParams.getFq(), 1, 0, "score", "asc");
        //now cycle through and get all the facets
        List<FacetField> facets = qr.getFacetFields();
        for (FacetField facet : facets) {
            if (facet.getValues() != null) {
                for (FacetField.Count ffc : facet.getValues()) {
                    if (ffc.getCount() > 0) {
                        uidStats.put(ffc.getName() != null ? ffc.getName() : "", new Integer((int) ffc.getCount()));
                    }
                }
            }
        }
        return uidStats;
    }

    /**
     * Gets the details about the SOLR fields using the LukeRequestHandler:
     * See http://wiki.apache.org/solr/LukeRequestHandler for more information
     */
    public Set<IndexFieldDTO> getIndexFieldDetails(String... fields) throws Exception {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("qt", "/admin/luke");

        params.set("tr", "luke.xsl");
        if(fields != null){
            params.set("fl", fields);
            params.set("numTerms", "1");
        }
        else {
            // TODO: We should be caching the result locally without calling Solr in this case, as it is called very often
            params.set("numTerms", "0");
        }
        QueryResponse response = query(params, queryMethod);
        return parseLukeResponse(response.toString(), fields != null);
    }

    /**
     * Returns the count of distinct values for the facets.  Uses groups for group counts.
     * Supports foffset and flimit for paging. Supports fsort 'count' or 'index'.
     * <p/>
     * TODO work out whether or not we should allow facet ranges to be downloaded....
     */
    public List<FacetResultDTO> getFacetCounts(SpatialSearchRequestParams searchParams) throws Exception {
        formatSearchQuery(searchParams);
        //add context information
        updateQueryContext(searchParams);
        String queryString = buildSpatialQueryString(searchParams);
        searchParams.setFacet(false);
        searchParams.setPageSize(0);

        //get facet group counts
        SolrQuery query = initSolrQuery(searchParams, false, null);
        query.setQuery(queryString);
        query.setFields(null);
        //now use the supplied facets to add groups to the query
        query.add("group", "true");
        query.add("group.ngroups", "true");
        query.add("group.limit", "0");
        query.setRows(0);
        searchParams.setPageSize(0);

        //exclude multivalue fields
        Set<IndexFieldDTO> fields = getIndexedFields();
        for (String facet : searchParams.getFacets()) {
            for (IndexFieldDTO f : fields) {
                if (f.getName().equals(facet) && !f.isMultivalue()) {
                    query.add("group.field", facet);
                }
            }
        }

        QueryResponse response = runSolrQuery(query, searchParams);
        GroupResponse groupResponse = response.getGroupResponse();

        Map<String, Integer> ngroups = new HashMap<String, Integer>();
        for (GroupCommand gc : groupResponse.getValues()) {
            ngroups.put(gc.getName(), gc.getNGroups());
        }

        //include paged facets when flimit > 0
        Collection<FacetResultDTO> facetResults = new ArrayList<FacetResultDTO>();
        if (searchParams.getFlimit() > 0) {
            searchParams.setFacet(true);
            SolrQuery facetQuery = initSolrQuery(searchParams, false, null);
            facetQuery.setQuery(queryString);
            facetQuery.setFields(null);
            facetQuery.setSortField(searchParams.getSort(), ORDER.valueOf(searchParams.getDir()));
            QueryResponse qr = runSolrQuery(facetQuery, searchParams);
            SearchResultDTO searchResults = processSolrResponse(searchParams, qr, facetQuery, OccurrenceIndex.class);
            facetResults = searchResults.getFacetResults();
            if (facetResults != null) {
                for (FacetResultDTO fr : facetResults) {
                    Integer count = ngroups.get(fr.getFieldName());
                    if (count != null) fr.setCount(count);
                }
            }
        } else {
            //only return group counts
            for (GroupCommand gc : groupResponse.getValues()) {
                facetResults.add(new FacetResultDTO(gc.getName(), null, gc.getNGroups()));
            }
        }

        return new ArrayList<FacetResultDTO>(facetResults);
    }

    @Override
    public Set<IndexFieldDTO> getIndexedFields() throws Exception {
        return getIndexedFields(false);
    }

    /**
     * Returns details about the fields in the index.
     */
    public Set<IndexFieldDTO> getIndexedFields(boolean update) throws Exception {
        Set<IndexFieldDTO> result = indexFields;
        if(result == null || update) {
            synchronized(solrIndexVersionLock) {
                result = indexFields;
                if(result == null || update) {
                    result = indexFields = getIndexFieldDetails(null);
                    Map<String, IndexFieldDTO> resultMap = new HashMap<String, IndexFieldDTO>();
                    for(IndexFieldDTO field: result) {
                        resultMap.put(field.getName(), field);
                    }
                    indexFieldMap = resultMap;
                }
            }
        }
        return result;
    }

    @Override
    public Map<String, IndexFieldDTO> getIndexedFieldsMap() throws Exception {
        // Refresh/populate the map if necessary
        getIndexedFields();
        return indexFieldMap;
    }

    /**
     * parses the response string from the service that returns details about the indexed fields
     * @param str
     * @return
     */
    private  Set<IndexFieldDTO> parseLukeResponse(String str, boolean includeCounts) {
        //update index version
        Pattern indexVersion = Pattern.compile("(?:version=)([0-9]{1,})");
        try {
            Matcher indexVersionMatcher = indexVersion.matcher(str);
            if (indexVersionMatcher.find(0)) {
                solrIndexVersion = Long.parseLong(indexVersionMatcher.group(1));
                solrIndexVersionTime = System.currentTimeMillis();
            }
        } catch (Exception e) {}

        Set<IndexFieldDTO> fieldList = includeCounts?new java.util.LinkedHashSet<IndexFieldDTO>():new java.util.TreeSet<IndexFieldDTO>();

        Pattern typePattern = Pattern.compile("(?:type=)([a-z]{1,})");

        Pattern schemaPattern = Pattern.compile("(?:schema=)([a-zA-Z\\-]{1,})");

        Pattern distinctPattern = Pattern.compile("(?:distinct=)([0-9]{1,})");

        String[] fieldsStr = str.split("fields=\\{");

        Map<String, String> indexToJsonMap = new OccurrenceIndex().indexToJsonMap();

        for(String fieldStr : fieldsStr) {
            if(!StringUtils.isEmpty(fieldStr)) {
                String[] fields = includeCounts ? fieldStr.split("\\}\\},") : fieldStr.split("\\},");

                for (String field : fields) {
                    formatIndexField(field, null, fieldList, typePattern, schemaPattern, indexToJsonMap, distinctPattern);
                }
            }
        }

        // add CASSANDRA fields that are not indexed
        for (String cassandraField : Store.getStorageFieldMap().keySet()) {
            boolean found = false;
            //ignore fields with multiple items
            if (cassandraField != null && !cassandraField.contains(",")) {
                for (IndexFieldDTO field : fieldList) {
                    if (field.isIndexed() || field.isStored()) {
                        if (field.getDownloadName() != null && field.getDownloadName().equals(cassandraField)) {

                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    formatIndexField(cassandraField, cassandraField, fieldList, typePattern, schemaPattern, indexToJsonMap, distinctPattern);
                }
            }
        }
        return fieldList;
    }

    private void formatIndexField(String indexField, String cassandraField, Set<IndexFieldDTO> fieldList, Pattern typePattern,
                                  Pattern schemaPattern, Map indexToJsonMap, Pattern distinctPattern) {

        if(!StringUtils.isEmpty(indexField)) {
            IndexFieldDTO f = new IndexFieldDTO();

            String fieldName = indexField.split("=")[0];
            String type = null;
            String schema = null;
            Matcher typeMatcher = typePattern.matcher(indexField);
            if (typeMatcher.find(0)) {
                type = typeMatcher.group(1);
            }

            Matcher schemaMatcher = schemaPattern.matcher(indexField);
            if (schemaMatcher.find(0)) {
                schema = schemaMatcher.group(1);
            }

            //don't allow the sensitive coordinates to be exposed via ws and don't allow index fields without schema
            if (fieldName != null && !fieldName.startsWith("sensitive_") && (cassandraField != null || schema != null)) {

                f.setName(fieldName);
                if (type != null) f.setDataType(type);
                else f.setDataType("string");

                //interpret the schema information
                if (schema != null) {
                    f.setIndexed(schema.contains("I"));
                    f.setStored(schema.contains("S"));
                    f.setMultivalue(schema.contains("M"));
                }

                //now add the i18n and associated strings to the field.
                //1. description: display name from fieldName= in i18n
                //2. info: details about this field from description.fieldName= in i18n
                //3. dwcTerm: DwC field name for this field from dwc.fieldName= in i18n
                //4. jsonName: json key as returned by occurrences/search
                //5. downloadField: biocache-store column name that is usable in DownloadRequestParams.fl
                //if the field has (5) downloadField, use it to find missing (1), (2) or (3)
                //6. downloadDescription: the column name when downloadField is used in
                //   DownloadRequestParams.fl and a translation occurs
                //7. i18nValues: true | false, indicates that the values returned by this field can be
                //   translated using facetName.value= in /facets/i18n
                //8. class value for this field
                if (layersPattern.matcher(fieldName).matches()) {
                    String description = layersService.getLayerNameMap().get(fieldName);
                    f.setDescription(description);
                } else {
                    //(5) check as a downloadField
                    String downloadField = cassandraField != null ? cassandraField : Store.getIndexFieldMap().get(fieldName);
                    //exclude compound fields
                    if (downloadField != null && downloadField.contains(",")) downloadField = null;
                    if (downloadField != null) {
                        f.setDownloadName(downloadField);

                        //(6) downloadField description
                        String downloadFieldDescription = messageSource.getMessage(downloadField, null, "", Locale.getDefault());
                        if (downloadFieldDescription.length() > 0) {
                            f.setDownloadDescription(downloadFieldDescription);
                        }
                    }

                    //(1) check as a field name
                    String description = messageSource.getMessage("facet." + fieldName, null, "", Locale.getDefault());
                    if (description.length() > 0 && downloadField == null) {
                        f.setDescription(description);
                    } else if (downloadField != null) {
                        description = messageSource.getMessage(downloadField, null, "", Locale.getDefault());
                        if (description.length() > 0) {
                            f.setDescription(description);
                        }
                    }

                    //(2) check as a description
                    String info = messageSource.getMessage("description." + fieldName, null, "", Locale.getDefault());
                    if (info.length() > 0 && downloadField == null) {
                        f.setInfo(info);
                    } else if (downloadField != null) {
                        info = messageSource.getMessage("description." + downloadField, null, "", Locale.getDefault());
                        if (info.length() > 0) {
                            f.setInfo(info);
                        }
                    }

                    //(3) check as a dwcTerm
                    String dwcTerm = messageSource.getMessage("dwc." + fieldName, null, "", Locale.getDefault());
                    if (dwcTerm.length() > 0 && downloadField == null) {
                        f.setDwcTerm(dwcTerm);
                    } else if (downloadField != null) {
                        dwcTerm = messageSource.getMessage("dwc." + downloadField, null, "", Locale.getDefault());
                        if (dwcTerm.length() > 0) {
                            f.setDwcTerm(dwcTerm);
                        }
                    }

                    //(4) check as json name
                    String json = (String) indexToJsonMap.get(fieldName);
                    if (json != null && downloadField == null) {
                        f.setJsonName(json);
                    }

                    //(7) has lookupValues in i18n
                    String i18nValues = messageSource.getMessage("i18nvalues." + fieldName, null, "", Locale.getDefault());
                    if (i18nValues.length() > 0) {
                        f.setI18nValues("true".equalsIgnoreCase(i18nValues));
                    }

                    //(8) get class
                    String classs = messageSource.getMessage("class." + fieldName, null, "", Locale.getDefault());
                    if (classs.length() > 0 && downloadField == null) {
                        f.setClasss(classs);
                    } else if (downloadField != null) {
                        classs = messageSource.getMessage("class." + downloadField, null, "", Locale.getDefault());
                        if (classs.length() > 0) {
                            f.setClasss(classs);
                        }
                    }
                }
                fieldList.add(f);
            }

            Matcher distinctMatcher = distinctPattern.matcher(indexField);
            if (distinctMatcher.find(0)) {
                Integer distinct = Integer.parseInt(distinctMatcher.group(1));
                f.setNumberDistinctValues(distinct);
            }
        }
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#findByFulltext(SpatialSearchRequestParams)
     */
    @Override
    public SolrDocumentList findByFulltext(SpatialSearchRequestParams searchParams) throws Exception {
        SolrDocumentList sdl = null;

        try {
            //String queryString = formatSearchQuery(query);
            formatSearchQuery(searchParams);
            //add context information
            updateQueryContext(searchParams);
            String queryString = buildSpatialQueryString(searchParams);
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.setQuery(queryString);
            solrQuery.setFields(searchParams.getFl());
            solrQuery.setFacet(false);
            solrQuery.setRows(searchParams.getPageSize());

            sdl = runSolrQuery(solrQuery, searchParams).getResults();
        } catch (SolrServerException ex) {
            logger.error("Problem communicating with SOLR server. " + ex.getMessage(), ex);
        }

        return sdl;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#getStatistics(SpatialSearchRequestParams)
     */
    public Map<String, FieldStatsInfo> getStatistics(SpatialSearchRequestParams searchParams) throws Exception{
        String[] values = new String[2];
        try{
            formatSearchQuery(searchParams);
            //add context information
            updateQueryContext(searchParams);
            String queryString = buildSpatialQueryString((SpatialSearchRequestParams)searchParams);
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.setQuery(queryString);
            for(String field: searchParams.getFacets()){
                solrQuery.setGetFieldStatistics(field);
            }
            QueryResponse qr = runSolrQuery(solrQuery, searchParams);

            logger.debug(qr.getFieldStatsInfo());

            return qr.getFieldStatsInfo();

        } catch (SolrServerException ex) {
            logger.error("Problem communicating with SOLR server. " + ex.getMessage(), ex);
        }
        return null;
    }

    @Cacheable(cacheName = "legendCache")
    public List<LegendItem> getLegend(SpatialSearchRequestParams searchParams, String facetField, String [] cutpoints) throws Exception {
        List<LegendItem> legend = new ArrayList<LegendItem>();

        formatSearchQuery(searchParams);
        logger.info("search query: " + searchParams.getFormattedQuery());

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setQuery(buildSpatialQueryString(searchParams));
        solrQuery.setRows(0);
        solrQuery.setFacet(true);

        //is facet query?
        if(cutpoints == null) {
            //special case for the decade
            if(DECADE_FACET_NAME.equals(facetField))
                initDecadeBasedFacet(solrQuery, "occurrence_year");
            else
                solrQuery.addFacetField(facetField);
        } else {
            solrQuery.addFacetQuery("-" + facetField + ":[* TO *]");

            for(int i=0;i<cutpoints.length;i+=2) {
                solrQuery.addFacetQuery(facetField + ":[" + cutpoints[i] + " TO " + cutpoints[i+1] + "]");
            }
        }

        solrQuery.setFacetMinCount(1);
        solrQuery.setFacetLimit(-1);//MAX_DOWNLOAD_SIZE);  // unlimited = -1

        //add the context information
        updateQueryContext(searchParams);

        solrQuery.setFacetMissing(true);

        QueryResponse qr = runSolrQuery(solrQuery, searchParams.getFq(), 1, 0, "score", "asc");
        List<FacetField> facets = qr.getFacetFields();
        if (facets != null) {
            for (FacetField facet : facets) {
                List<FacetField.Count> facetEntries = facet.getValues();
                if (facet.getName().contains(facetField) && (facetEntries != null) && (facetEntries.size() > 0)) {
                    int i = 0;
                    for (i=0;i<facetEntries.size();i++) {
                        FacetField.Count fcount = facetEntries.get(i);
                        if (fcount.getCount() > 0) {
                            String fq = facetField + ":\"" + fcount.getName() + "\"";
                            if (fcount.getName() == null) {
                                fq = "-" + facetField + ":[* TO *]";
                            }
                            legend.add(new LegendItem(fcount.getName(), fcount.getCount(), fq));
                        }
                    }
                    break;
                }
            }
        }
        //check if we have query based facets
        Map<String, Integer> facetq = qr.getFacetQuery();
        if(facetq != null && facetq.size() > 0) {
            for(Entry<String, Integer> es : facetq.entrySet()) {
                legend.add(new LegendItem(es.getKey(), es.getValue(), es.getKey()));
            }
        }

        //check to see if we have a date range facet
        List<FacetField> facetDates = qr.getFacetDates();
        if (facetDates != null && !facetDates.isEmpty()) {
            FacetField ff = facetDates.get(0);
            String firstDate = null;
            for(FacetField.Count facetEntry: ff.getValues()){
                String startDate = facetEntry.getName();
                if(firstDate == null) {
                    firstDate = startDate;
                }
                String finishDate;
                if(DECADE_PRE_1850_LABEL.equals(startDate)){
                    startDate = "*";
                    finishDate = firstDate;
                } else {
                    int startYear = Integer.parseInt(startDate.substring(0,4));
                    finishDate = (startYear - 1) + "-12-31T23:59:59Z";
                }
                legend.add(
                        new LegendItem(facetEntry.getName(),
                                facetEntry.getCount(),
                                "occurrence_year:[" + startDate+" TO " + finishDate + "]")
                );
            }
        }
        return legend;
    }

    public FacetField getFacet(SpatialSearchRequestParams searchParams, String facet) throws Exception {
        formatSearchQuery(searchParams);
        logger.info("search query: " + searchParams.getFormattedQuery());

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setQuery(buildSpatialQueryString(searchParams));
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.addFacetField(facet);
        solrQuery.setFacetMinCount(1);
        solrQuery.setFacetLimit(-1); //MAX_DOWNLOAD_SIZE);  // unlimited = -1

        //add the context information
        updateQueryContext(searchParams);

        QueryResponse qr = runSolrQuery(solrQuery, searchParams.getFq(), 1, 0, "score", "asc");
        return qr.getFacetFields().get(0);
    }

    public List<DataProviderCountDTO> getDataProviderList(SpatialSearchRequestParams requestParams) throws Exception {
        List<DataProviderCountDTO> dataProviderList = new ArrayList<DataProviderCountDTO>();
        FacetField facet = getFacet(requestParams, "data_provider_uid");
        String[] oldFq = requestParams.getFacets();
        if(facet != null) {
            String[] dp = new String[1];
            List<FacetField.Count> facetEntries = facet.getValues();
            if (facetEntries != null && facetEntries.size() > 0) {
                for (int i = 0; i < facetEntries.size(); i++) {
                    FacetField.Count fcount = facetEntries.get(i);

                    //get data_provider value
                    dp[0] = fcount.getAsFilterQuery();
                    requestParams.setFq(dp);
                    String dataProviderName = getFacet(requestParams, "data_provider").getValues().get(0).getName();
                    dataProviderList.add(new DataProviderCountDTO(fcount.getName(), dataProviderName, fcount.getCount()));
                }
            }
        }
        requestParams.setFacets(oldFq);
        return dataProviderList;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#findAllSpecies(SpatialSearchRequestParams)
     */
    @Override
    public List<TaxaCountDTO> findAllSpecies(SpatialSearchRequestParams requestParams) throws Exception {
        formatSearchQuery(requestParams);
        //add the context information
        List<String> facetFields = new ArrayList<String>();
        facetFields.add(NAMES_AND_LSID);

        logger.debug("The species count query " + requestParams.getFormattedQuery());

        List<String> fqList = new ArrayList<String>();
        //only add the FQ's if they are not the default values
        if(requestParams.getFq().length>0 && (requestParams.getFq()[0]).length()>0) {
            org.apache.commons.collections.CollectionUtils.addAll(fqList, requestParams.getFq());
        }

        String query = buildSpatialQueryString(requestParams);
        List<TaxaCountDTO> speciesWithCounts = getSpeciesCounts(
                query,
                fqList,
                facetFields,
                requestParams.getPageSize(),
                requestParams.getStart(),
                requestParams.getSort(),
                requestParams.getDir()
        );

        return speciesWithCounts;
    }

    public Map<String, Integer> getOccurrenceCountsForTaxa(List<String> taxa) throws Exception {
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQueryType("standard");
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.setFacetLimit(taxa.size());
        StringBuilder sb = new StringBuilder();
        Map<String,Integer> counts = new HashMap<String,Integer>();
        Map<String, String> lftToGuid = new HashMap<String,String>();
        for(String lsid : taxa){
            //get the lft and rgt value for the taxon
            String[] values = searchUtils.getTaxonSearch(lsid);
            //first value is the search string
            if(sb.length()>0)
                sb.append(" OR ");
            sb.append(values[0]);
            lftToGuid.put(values[0], lsid);
            //add the query part as a facet
            solrQuery.add("facet.query", values[0]);
        }
        solrQuery.setQuery(sb.toString());

        //solrQuery.add("facet.query", "confidence:" + os.getRange());
        QueryResponse qr = runSolrQuery(solrQuery, null, 1, 0, "score", "asc");
        Map<String, Integer> facetQueries = qr.getFacetQuery();
        for(String facet:facetQueries.keySet()){
            //add all the counts based on the query value that was substituted
            String lsid = lftToGuid.get(facet);
            Integer count = facetQueries.get(facet);
            if(lsid != null && count!= null)
                counts.put(lsid,  count);
        }

        logger.debug(facetQueries);

        return counts;
    }

    /**
     * @return the maxEndemicQueryThreads for endemic queries
     */
    public Integer getMaxEndemicQueryThreads() {
      return maxEndemicQueryThreads;
    }

    /**
     * @param maxEndemicQueryThreads the maxEndemicQueryThreads to set for endemic queries
     */
    public void setMaxEndemicQueryThreads(Integer maxEndemicQueryThreads) {
      this.maxEndemicQueryThreads = Objects.requireNonNull(maxEndemicQueryThreads, "Max endemic multipart threads cannot be null");
    }

    /**
     * @return the maxSolrDownloadThreads for solr download queries
     */
    public Integer getMaxSolrDownloadThreads() {
      return maxSolrDownloadThreads;
    }

    /**
     * @param maxSolrDownloadThreads the maxSolrDownloadThreads to set for solr download queries
     */
    public void setMaxSolrDownloadThreads(Integer maxSolrDownloadThreads) {
      this.maxSolrDownloadThreads = Objects.requireNonNull(maxSolrDownloadThreads, "Max solr download threads cannot be null");
    }

    /**
     * @return the throttle
     */
    public Integer getThrottle() {
        return throttle;
    }

    /**
     * @param throttle the throttle to set
     */
    public void setThrottle(Integer throttle) {
        this.throttle = Objects.requireNonNull(throttle, "Throttle cannot be null");
    }

    private QueryResponse query(SolrParams query, SolrRequest.METHOD queryMethod) throws SolrServerException {
        int retry = 0;
        QueryResponse qr = null;
        while (retry < maxRetries && qr == null) {
            retry++;
            try {
                qr = getServer().query(query, queryMethod == null ? this.queryMethod : queryMethod); // can throw exception
            } catch (SolrServerException e) {
                //want to retry IOException and Proxy Error
                if (retry < maxRetries && (e.getMessage().contains("IOException") || e.getMessage().contains("Proxy Error"))) {
                    if (retryWait > 0) {
                        try {
                            Thread.sleep(retryWait);
                        } catch (InterruptedException ex) {
                            // If the Thread sleep is interrupted, we shouldn't attempt to continue
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                } else {
                    //throw all other errors
                    throw e;
                }
            }
        }

        return qr;
    }

    /**
     * Generate SOLR query from a taxa[] query
     *
     * @param taxaQueries
     * @param guidsForTaxa
     * @return
     */
    String createQueryWithTaxaParam(List taxaQueries, List guidsForTaxa) {
        StringBuilder query = new StringBuilder();

        if (taxaQueries.size() != guidsForTaxa.size()) {
            // Both Lists must the same size
            throw new IllegalArgumentException("Arguments (List) are not the same size: taxaQueries.size() (${taxaQueries.size()}) != guidsForTaxa.size() (${guidsForTaxa.size()})");
        }

        if (taxaQueries.size() > 1) {
            // multiple taxa params (array)
            query.append("(");
            for (int i = 0; i < guidsForTaxa.size(); i++) {
                String guid = (String) guidsForTaxa.get(i);
                if (i > 0) query.append(" OR ");
                if (guid != null && !guid.isEmpty()) {
                    query.append("lsid:").append(guid);
                } else {
                    query.append("text:").append(taxaQueries.get(i));
                }
            }
            query.append(")");
        } else if (guidsForTaxa.size() > 0) {
            // single taxa param
            String taxa = (String) taxaQueries.get(0);
            String guid = (String) guidsForTaxa.get(0);
            if (guid != null && !guid.isEmpty()) {
                query.append("lsid:").append(guid);
            } else if (taxa != null && !taxa.isEmpty()) {
                query.append("text:").append(taxa);
            }
        }

        return query.toString();
    }

    /**
     * Get the SOLR index version. Trigger a background refresh on a timeout.
     *
     * Forcing an updated value will perform a new SOLR query for each request to be run in the foreground.
     *
     * @return
     * @param force
     */
    public Long getIndexVersion(Boolean force) {
        Thread t = null;
        synchronized (solrIndexVersionLock) {
            boolean immediately = solrIndexVersionTime == 0;

            if (force || solrIndexVersionTime < System.currentTimeMillis() - solrIndexVersionRefreshTime) {
                solrIndexVersionTime = System.currentTimeMillis();

                t = new Thread() {
                    @Override
                    public void run() {
                        try {
                            getIndexFieldDetails(null);
                        } catch (Exception e) {
                            logger.error("Failed to update solrIndexVersion", e);
                        }
                    }
                };

                if (immediately) {
                    //wait with lock
                    t.start();
                    try {
                        t.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("Failed to update solrIndexVersion", e);
                    }
                } else if (!force) {
                    //run in background
                    t.start();
                }
            }
        }

        if (force && t != null) {
            //wait without lock
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Failed to update solrIndexVersion", e);
            }
        }

        return solrIndexVersion;
    }

    /**
     * Perform grouped facet query.
     *
     * facets is the list of grouped facets required
     * flimit restricts the number of groups returned
     * pageSize restricts the number of docs in each group returned
     * fl is the list of fields in the returned docs
     *
     */
    public List<GroupFacetResultDTO> searchGroupedFacets(SpatialSearchRequestParams searchParams) throws Exception {
        formatSearchQuery(searchParams);
        //add context information
        updateQueryContext(searchParams);
        String queryString = buildSpatialQueryString(searchParams);
        searchParams.setFacet(false);

        //get facet group counts
        SolrQuery query = initSolrQuery(searchParams, false, null);
        query.setQuery(queryString);
        query.setFields(null);
        //now use the supplied facets to add groups to the query
        query.add("group", "true");
        query.add("group.ngroups", "true");
        query.add("group.limit", String.valueOf(searchParams.getPageSize()));
        query.setRows(searchParams.getFlimit());
        query.setFields(searchParams.getFl());
        for (String facet : searchParams.getFacets()) {
            query.add("group.field", facet);
        }
        QueryResponse response = runSolrQuery(query, searchParams);
        GroupResponse groupResponse = response.getGroupResponse();

        List<GroupFacetResultDTO> output = new ArrayList();
        for (GroupCommand gc : groupResponse.getValues()) {
            List<GroupFieldResultDTO> list = new ArrayList<GroupFieldResultDTO>();

            String facet = gc.getName();
            for (Group v : gc.getValues()) {
                List<OccurrenceIndex> docs = (new DocumentObjectBinder()).getBeans(OccurrenceIndex.class, v.getResult());

                //build facet displayName and fq
                String value = v.getGroupValue();
                Long count = v.getResult() != null ? v.getResult().getNumFound() : 0L;
                if (value == null) {
                    list.add(new GroupFieldResultDTO("", count, "-" + facet + ":*", docs));
                } else {
                    list.add(new GroupFieldResultDTO(getFacetValueDisplayName(facet, value), count, facet + ":\"" + value + "\"", docs));
                }
            }

            output.add(new GroupFacetResultDTO(gc.getName(), list, gc.getNGroups()));
        }

        return output;
    }

    /**
     * Generates a FQ value for use in the returning query response.
     *
     * @param facet
     * @param value
     * @return
     */
    String getFormattedFqQuery(String facet, String value){
        if(facet.equals(OCCURRENCE_YEAR_INDEX_FIELD)){

            if(value.equals(DECADE_PRE_1850_LABEL)){
                return facet + ":"  + "[* TO " + DECADE_FACET_START_DATE + "]";
            } else {
                SimpleDateFormat sdf = new SimpleDateFormat(SOLR_DATE_FORMAT);
                try {
                    Date date = sdf.parse(value);
                    Date endDate = DateUtils.addYears(date, 10);
                    endDate = DateUtils.addMilliseconds(endDate, -1);
                    return facet + ":"  + "[" + value + " TO " + sdf.format(endDate) + "]";
                } catch (ParseException e){
                    //do nothing
                }
            }
        }

        return facet + ":\"" + value + "\"";
    }

    /**
     * Formats the facet value using i18n where possible.
     * Special logic here for formating decades.
     *
     * @param facet
     * @param value
     * @return
     */
    String getFacetValueDisplayName(String facet, String value) {
        if(facet.endsWith("_uid")) {
            return searchUtils.getUidDisplayString(facet, value, false);
        } else if(facet.equals("occurrence_year")){
            try {
                if(DECADE_PRE_1850_LABEL.equals(value)){
                    return messageSource.getMessage("decade.pre.start", null,  "pre 1850", null); // "pre 1850";
                }
                SimpleDateFormat sdf = new SimpleDateFormat(SOLR_DATE_FORMAT);
                Date date = sdf.parse(value);
                SimpleDateFormat df = new SimpleDateFormat("yyyy");
                String year = df.format(date);
                return year + "-" + (Integer.parseInt(year) + 9);
            } catch (ParseException pe){
                return facet;
            }
            //1850-01-01T00:00:00Z
        } else if(getAuthIndexFields().contains(facet)){
            //if the facet field is collector or assertion_user_id we need to perform the substitution
            return authService.getDisplayNameFor(value);
        } else {
            // Commented out for we want to do the translation elsewhere.
            // return messageSource.getMessage(facet + "." + value, null, value, null);
            return value;
        }
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#searchPivot(au.org.ala.biocache.dto.SpatialSearchRequestParams)
     */
    public List<FacetPivotResultDTO> searchPivot(SpatialSearchRequestParams searchParams) throws Exception {
        String pivot = StringUtils.join(searchParams.getFacets(), ",");
        searchParams.setFacets(new String[]{});

        formatSearchQuery(searchParams);
        //add context information
        updateQueryContext(searchParams);
        String queryString = buildSpatialQueryString(searchParams);
        searchParams.setFacet(true);

        //get facet group counts
        SolrQuery query = initSolrQuery(searchParams, false, null);
        query.setQuery(queryString);
        query.setFields(null);
        //now use the supplied facets to add groups to the query
        query.add("facet.pivot", pivot);
        query.add("facet.pivot.mincount", "1");
        query.add("facet.missing", "true");
        query.setRows(0);
        searchParams.setPageSize(0);
        QueryResponse response = runSolrQuery(query, searchParams);
        NamedList<List<PivotField>> result = response.getFacetPivot();

        List<FacetPivotResultDTO> output = new ArrayList();
        for (Entry<String, List<PivotField>> pfl : result) {
            List<PivotField> list = pfl.getValue();
            if (list != null && list.size() > 0) {
                output.add(new FacetPivotResultDTO(
                        list.get(0).getField(),
                        getFacetPivotResults(list),
                        null,
                        (int) response.getResults().getNumFound())
                );
            }

            //should only be one result
            break;
        }

        return output;
    }

    /**
     * Read nested pivot results.
     *
     * @param pfl
     * @return
     */
    private List<FacetPivotResultDTO> getFacetPivotResults(List<PivotField> pfl) {
        if (pfl == null || pfl.size() == 0) {
            return null;
        }

        List<FacetPivotResultDTO> list = new ArrayList<FacetPivotResultDTO>();
        for (PivotField pf : pfl) {
            String value = pf.getValue() != null ? pf.getValue().toString() : null;
            if (pf.getPivot() == null || pf.getPivot().size() == 0) {
                list.add(new FacetPivotResultDTO(null, null, value, pf.getCount()));
            } else {
                list.add(new FacetPivotResultDTO(pf.getPivot().get(0).getField(), getFacetPivotResults(pf.getPivot()), value, pf.getCount()));
            }
        }

        return list;
    }

    public StringBuilder getAllQAFields() {
        //include all assertions
        StringBuilder qasb = new StringBuilder();
        ErrorCode[] errorCodes = Store.retrieveAssertionCodes();
        Arrays.sort(errorCodes, new Comparator<ErrorCode>() {
            @Override
            public int compare(ErrorCode o1, ErrorCode o2) {
                return o1.getName().compareToIgnoreCase(o2.getName());
            }
        });
        for (ErrorCode assertionCode : errorCodes) {
            if (qasb.length() > 0)
                qasb.append(",");
            qasb.append(assertionCode.getName());
        }

        return qasb;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#searchStat
     */
    public List<FieldStatsItem> searchStat(SpatialSearchRequestParams searchParams, String field, String facet) throws Exception {
        searchParams.setFacets(new String[]{});

        formatSearchQuery(searchParams);
        //add context information
        updateQueryContext(searchParams);
        String queryString = buildSpatialQueryString(searchParams);

        if (facet != null) searchParams.setFacet(true);

        //get facet group counts
        SolrQuery query = initSolrQuery(searchParams, false, null);
        query.setQuery(queryString);
        query.setFields(null);
        //query.setFacetLimit(-1);

        //stats parameters
        query.add("stats", "true");
        if (facet != null) query.add("stats.facet", facet);
        query.add("stats.field", field);

        query.setRows(0);
        searchParams.setPageSize(0);
        QueryResponse response = runSolrQuery(query, searchParams);

        List<FieldStatsItem> output = new ArrayList();
        if (facet != null && response.getFieldStatsInfo().size() > 0) {
            for (FieldStatsInfo f : response.getFieldStatsInfo().values().iterator().next().getFacets().values().iterator().next()) {
                FieldStatsItem item = new FieldStatsItem(f);
                if (f.getName() == null) {
                    item.setFq("-" + facet + ":*");
                } else {
                    item.setFq(facet + ":\"" + f.getName() + "\"");
                }
                item.setLabel(f.getName());
                output.add(item);
            }
        } else {
            if (response.getFieldStatsInfo().size() > 0) {
                output.add(new FieldStatsItem(response.getFieldStatsInfo().values().iterator().next()));
            }
        }

        return output;
    }

    /**
     * @see au.org.ala.biocache.dao.SearchDAO#getColours
     */
    @Cacheable(cacheName = "getColours")
    public List<LegendItem> getColours(SpatialSearchRequestParams request, String colourMode) throws Exception {
        List<LegendItem> colours = new ArrayList<LegendItem>();
        if (colourMode.equals("grid")) {
            for (int i = 0; i <= 500; i += 100) {
                LegendItem li;
                if (i == 0) {
                    li = new LegendItem(">0", 0, null);
                } else {
                    li = new LegendItem(String.valueOf(i), 0, null);
                }
                li.setColour((((500 - i) / 2) << 8) | 0x00FF0000);
                colours.add(li);
            }
        } else {
            SpatialSearchRequestParams requestParams = new SpatialSearchRequestParams();
            requestParams.setFormattedQuery(request.getFormattedQuery());
            requestParams.setWkt(request.getWkt());
            requestParams.setRadius(request.getRadius());
            requestParams.setLat(request.getLat());
            requestParams.setLon(request.getLon());
            requestParams.setQ(request.getQ());
            requestParams.setQc(request.getQc());
            requestParams.setFq(qidCacheDao.getFq(request));
            requestParams.setFoffset(-1);

            //test for cutpoints on the back of colourMode
            String[] s = colourMode.split(",");
            String[] cutpoints = null;
            if (s.length > 1) {
                cutpoints = new String[s.length - 1];
                System.arraycopy(s, 1, cutpoints, 0, cutpoints.length);
            }
            if (s[0].equals("-1") || s[0].equals("grid")) {
                return null;
            } else {
                List<LegendItem> legend = getLegend(requestParams, s[0], cutpoints);

                if (cutpoints == null) {     //do not sort if cutpoints are provided
                    java.util.Collections.sort(legend);
                }
                int i = 0;
                int offset = 0;
                for (i = 0; i < legend.size() && i < ColorUtil.colourList.length - 1; i++) {
                    colours.add(new LegendItem(legend.get(i).getName(), legend.get(i).getCount(), legend.get(i).getFq()));
                    int colour = DEFAULT_COLOUR;
                    if (cutpoints == null) {
                        colour = ColorUtil.colourList[i];
                    } else if (cutpoints != null && i - offset < cutpoints.length) {
                        if (StringUtils.isEmpty(legend.get(i).getName()) || legend.get(i).getName().equals("Unknown") || legend.get(i).getName().startsWith("-")) {
                            offset++;
                        } else {
                            colour = ColorUtil.getRangedColour(i - offset, cutpoints.length / 2);
                        }
                    }
                    colours.get(colours.size() - 1).setColour(colour);
                }
            }
        }

        return colours;
    }
}
