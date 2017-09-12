biocache-service [![Build Status](https://travis-ci.org/AtlasOfLivingAustralia/biocache-service.svg?branch=master)](http://travis-ci.org/AtlasOfLivingAustralia/biocache-service)
================

Occurrence &amp; mapping webservices.

Theses services are documented here http://api.ala.org.au/apps/biocache

## Versions

There are currently two supported version

* 1.9.x  - SOLR 4 and Cassandra 1.2.x. See the master branch.
* 2.x - SOLR 5 with SOLR Cloud support and Cassandra 3.x. See the 2.x branch.

## Pre-requisites
* [Maven](https://maven.apache.org/download.cgi)
* [Apache Tomcat](https://tomcat.apache.org/download-70.cgi)

## Running
cd to biocache-service

run
```console
mvn package -DskipTests=true
```
this creates war file to target folder

copy the war file to tomcat's webapps dir

run tomcat server
```console
bin/startup.sh
```
tomcat should now be serving under http://localhost:8080/biocache-service/
