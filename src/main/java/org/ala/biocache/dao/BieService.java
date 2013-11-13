/**************************************************************************
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
package org.ala.biocache.dao;

import java.util.List;

/**
 * Service layer interface for accessing species lookups.
 * 
 * @author "Nick dos Remedios <Nick.dosRemedios@csiro.au>"
 */
public interface BieService {
    /**
     * Lookup a GUID for a given scientific name (returns best match/accepted concept)
     * 
     * @param name
     * @return 
     */
    String getGuidForName(String name);

    /**
     * Lookup the accepted name for a GUID
     *
     * @return
     */
    String getAcceptedNameForGuide(String guid);
    
    /**
     * Lookup service for list of guids to names
     * @param guids
     * @return
     */
    List<String> getNamesForGuids(List<String> guids);
}
