/* *************************************************************************
 *  Copyright (C) 2011 Atlas of Living Australia
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

package au.org.ala.biocache.dto;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;

import au.org.ala.biocache.validate.LogType;

/**
 * Data Transfer Object to represent the request parameters required to download
 * the results of a search.
 *
 * @author "Natasha Carter <Natasha.Carter@csiro.au>"
 */
public class DownloadRequestParams extends SpatialSearchRequestParams {

    protected String email = "";
    protected String reason = "";
    protected String file = "data";
    /** CSV list of fields that should be downloaded.  If el or cl will need to map to appropriate column name */
    protected String fields = "uuid,catalogNumber,taxonConceptID.p,scientificName,vernacularName,scientificName.p," +
                              "taxonRank.p,vernacularName.p,kingdom.p,phylum.p,classs.p,order.p,family.p,genus.p," +
                              "species.p,subspecies.p,institutionCode,collectionCode,locality,decimalLatitude," +
                              "decimalLongitude,geodeticDatum,decimalLatitude.p,decimalLongitude.p," +
                              "coordinateUncertaintyInMeters.p,country.p,stateProvince.p," +
                              "minimumElevationInMeters.p,maximumElevationInMeters.p,minimumDepthInMeters.p," +
                              "maximumDepthInMeters.p,recordedBy,year.p,month.p,eventDate.p,basisOfRecord," +
                              "basisOfRecord.p,outlierForLayers.p,taxonomicIssue.p,geospatiallyKosher,cl1007,cl1012," +
                              "sex,behavior,life_stage,dynamic_properties";

    /** CSV list of extra fields to be added to the download - useful if wish to make use of default list */
    @Value("${download.extra.fields}")
    protected String extra = "";
    /** the CSV list of issue types to include in the download, defaults to all. Also supports none. */
    protected String qa = "all";
    /** The CSV separator to use */
    protected Character sep = ',';
    /** The CSV escape character to use */
    protected Character esc = '"';
    /** The header is to use darwin core headers (from messages.properties) */
    protected Boolean dwcHeaders = false;
    /** Include all available misc fields. For Cassandra downloads only. */
    protected Boolean includeMisc = false;

    @NotNull @LogType(type="reason")//@Range(min=0, max=10)
    protected Integer reasonTypeId = null;
    @LogType(type="source")
    protected Integer sourceTypeId = null;
    //The file type for the download file."shp" or "csv"
    @Pattern(regexp="(csv|shp|tsv)")
    protected String fileType = "csv";

    /**
     * Custom toString method to produce a String to be used as the request parameters
     * for the Biocache Service webservices
     *
     * @return request parameters string
     */
    @Override
    public String toString() {
        StringBuilder req = new StringBuilder(super.toString());
        req.append("&email=").append(email);
        req.append("&reason=").append(reason);
        req.append("&file=").append(file);
        req.append("&fields=").append(fields);
        req.append("&extra=").append(extra);
        if(reasonTypeId != null) {
            req.append("&reasonTypeId=").append(reasonTypeId);
        }
        if(sourceTypeId != null) {
            req.append("&sourceTypeId=").append(sourceTypeId);
        }
        if(!"csv".equals(fileType)){
            req.append("&fileType=").append(fileType);
        }
        if(!"all".equals(qa)){
            req.append("&qa=").append(qa);
        }
        if (dwcHeaders) {
            req.append("&dwcHeaders=true");
        }
        if (includeMisc) {
            req.append("&includeMisc=true");
        }

        return req.toString();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * @return the fields
     */
    public String getFields() {
        return fields;
    }

    /**
     * @param fields the fields to set
     */
    public void setFields(String fields) {
        this.fields = fields;
    }

    /**
     * @return the extra
     */
    public String getExtra() {
        return extra;
    }

    /**
     * @param extra the extra to set
     */
    public void setExtra(String extra) {
        this.extra = extra;
    }

    /**
     * @return the reasonTypeId
     */
    public Integer getReasonTypeId() {
        return reasonTypeId;
    }

    /**
     * @param reasonTypeId the reasonTypeId to set
     */
    public void setReasonTypeId(Integer reasonTypeId) {
        this.reasonTypeId = reasonTypeId;
    }

    /**
     * @return the sourceId
     */
    public Integer getSourceTypeId() {
        return sourceTypeId;
    }

    /**
     * @param sourceTypeId the sourceId to set
     */
    public void setSourceTypeId(Integer sourceTypeId) {
        this.sourceTypeId = sourceTypeId;
    }

    /**
     * @return the fileType
     */
    public String getFileType() {
        return fileType;
    }

    /**
     * @param fileType the fileType to set
     */
    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    /**
     * @return the qa
     */
    public String getQa() {
        return qa;
    }

    /**
     * @param qa the qa to set
     */
    public void setQa(String qa) {
        this.qa = qa;
    }

    public Character getEsc() {
        return esc;
    }

    public void setEsc(Character esc) {
        this.esc = esc;
    }

    public Character getSep() {
        return sep;
    }

    public void setSep(Character sep) {
        this.sep = sep;
    }

    public Boolean getDwcHeaders() {
        return dwcHeaders;
    }

    public void setDwcHeaders(Boolean dwcHeaders) {
        this.dwcHeaders = dwcHeaders;
    }

    public Boolean getIncludeMisc() {
        return includeMisc;
    }

    public void setIncludeMisc(Boolean includeMisc) {
        this.includeMisc = includeMisc;
    }
}
