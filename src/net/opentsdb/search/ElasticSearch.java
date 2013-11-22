// This file is part of OpenTSDB.
// Copyright (C) 2013  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.search;

import httpfailover.FailoverHttpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.HttpHost;
import org.apache.http.client.fluent.Async;
import org.apache.http.client.fluent.Content;
import org.apache.http.client.fluent.Request;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.TSDB;
import net.opentsdb.meta.Annotation;
import net.opentsdb.meta.TSMeta;
import net.opentsdb.meta.UIDMeta;
import net.opentsdb.search.SearchQuery.SearchType;
import net.opentsdb.stats.StatsCollector;
import net.opentsdb.utils.JSON;
import net.opentsdb.uid.UniqueId.UniqueIdType;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.stumbleupon.async.Deferred;

// This is all terrible but I don't have time to figure out how to inject
// only the extra data we need with Jackson.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = Visibility.PUBLIC_ONLY)
class TSMetaHack {
  private String tsuid = "";
  private UIDMeta metric = null;
  private ArrayList<UIDMeta> tags = null;
  private String display_name = "";
  private String description = "";
  private String notes = "";
  private long created = 0;
  private HashMap<String, String> custom = null;
  private String units = "";
  private String data_type = "";
  private int retention = 0;
  private double max = Double.NaN;
  private double min = Double.NaN; 
  private long last_received = 0;
  private long total_dps;
  private ArrayList<UIDMeta> keys = null;
  private ArrayList<UIDMeta> values = null;
  private ArrayList<String> kv_map = null;

  public TSMetaHack(TSMeta meta) {
    tsuid = meta.getTSUID();
    metric = meta.getMetric();
    tags = meta.getTags();
    display_name = meta.getDisplayName();
    description = meta.getDescription();
    notes = meta.getNotes();
    created = meta.getCreated();
    custom = meta.getCustom();
    units = meta.getUnits();
    data_type = meta.getDataType();
    retention = meta.getRetention();
    min = meta.getMax();
    max = meta.getMin();
    last_received = meta.getLastReceived();
    total_dps = meta.getTotalDatapoints();

    keys = new ArrayList<UIDMeta>();
    values = new ArrayList<UIDMeta>();
    kv_map = new ArrayList<String>();

    String key = null;
    String value = null;
    for (UIDMeta m : tags) {
      if (m.getType() == UniqueIdType.TAGK) {
        keys.add(m);
        key = "TAGK_" + m.getUID();
      } else if (m.getType() == UniqueIdType.TAGV) {
        values.add(m);
        value = "TAGV_" + m.getUID();
      }

      if (key != null && value != null) {
        kv_map.add(key + "=" + value);
        key = value = null;
      }
    }
  }

  /** @return the TSUID as a hex encoded string */
  public final String getTSUID() {
    return tsuid;
  }

  /** @return the metric UID meta object */
  public final UIDMeta getMetric() {
    return metric;
  }

  /** @return the tag UID meta objects in an array, tagk first, then tagv, etc */
  public final ArrayList<UIDMeta> getTags() {
    return tags;
  }

  /** @return optional display name */
  public final String getDisplayName() {
    return display_name;
  }

  /** @return optional description */
  public final String getDescription() {
    return description;
  }

  /** @return optional notes */
  public final String getNotes() {
    return notes;
  }

  /** @return when the TSUID was first recorded, Unix epoch */
  public final long getCreated() {
    return created;
  }

  /** @return optional custom key/value map, may be null */
  public final HashMap<String, String> getCustom() {
    return custom;
  }

  /** @return optional units */
  public final String getUnits() {
    return units;
  }

  /** @return optional data type */
  public final String getDataType() {
    return data_type;
  }

  /** @return optional retention, default of 0 means retain indefinitely */
  public final int getRetention() {
    return retention;
  }

  /** @return optional max value, set by the user */
  public final double getMax() {
    return max;
  }

  /** @return optional min value, set by the user */
  public final double getMin() {
    return min;
  }

  /** @return the last received timestamp, Unix epoch */
  public final long getLastReceived() {
    return last_received;
  }

  /** @return the total number of data points as tracked by the meta data */
  public final long getTotalDatapoints() {
    return this.total_dps;
  }

  public final ArrayList<UIDMeta> getKeys() {
    return keys;
  }
  public final ArrayList<UIDMeta> getValues() {
    return values;
  }

  @JsonProperty("kv_map")
  public final ArrayList<String> getKvMap() {
    return kv_map;
  }
}

public final class ElasticSearch extends SearchPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(ElasticSearch.class);

  private final ExecutorService threadpool = Executors.newFixedThreadPool(4);
  private final Async async = Async.newInstance().use(threadpool);
  
  private ImmutableList<HttpHost> hosts;
  private FailoverHttpClient httpClient;
  private String index = "opentsdb";
  private String tsmeta_type = "tsmeta";
  private String uidmeta_type = "uidmeta";
  private String annotation_type = "annotation";
  private ESPluginConfig config = null;
  
  /**
   * Default constructor
   */
  public ElasticSearch() {

  }
  
  /**
   * Initializes the search plugin, setting up the HTTP client pool and config
   * options.
   * @param tsdb The TSDB to which we belong
   * @return null if successful, otherwise it throws an exception
   * @throws IllegalArgumentException if a config value is invalid
   * @throws NumberFormatException if a config value is invalid
   */
  @Override
  public void initialize(final TSDB tsdb) {
    config = new ESPluginConfig(tsdb.getConfig());
    setConfiguration();

    // setup a connection pool for reuse
    PoolingClientConnectionManager http_pool = 
      new PoolingClientConnectionManager();
    http_pool.setDefaultMaxPerRoute(
        config.getInt("tsd.search.elasticsearch.pool.max_per_route"));
    http_pool.setMaxTotal(
        config.getInt("tsd.search.elasticsearch.pool.max_total"));
    httpClient = new FailoverHttpClient(http_pool);
  }
  
  /**
   * Queues the given TSMeta object for indexing
   * @param meta The meta data object to index
   * @return null
   */
  @Override
  public Deferred<Object> indexTSMeta(final TSMeta meta) {
    final StringBuilder uri = new StringBuilder("http://");
    uri.append(hosts.get(0).toHostString());
    uri.append("/").append(index).append("/").append(tsmeta_type).append("/");
    uri.append(meta.getTSUID()).append("?replication=async");
    
    final Request post = Request.Post(uri.toString())
      .bodyByteArray(JSON.serializeToBytes(new TSMetaHack(meta)));
    
    final Deferred<Object> result = new Deferred<Object>();
    async.execute(post, new AsyncCB(result));
    return result;
  }

  /**
   * Queues the given TSMeta object for deletion
   * @param meta The meta data object to delete
   * @return null
   */
  public Deferred<Object> deleteTSMeta(final String tsuid) {
    final StringBuilder uri = new StringBuilder("http://");
    uri.append(hosts.get(0).toHostString());
    uri.append("/").append(index).append("/").append(tsmeta_type).append("/");
    uri.append(tsuid).append("?replication=async");
    
    final Request delete = Request.Delete(uri.toString());
    
    final Deferred<Object> result = new Deferred<Object>();
    async.execute(delete, new AsyncCB(result));
    return result;
  }
  
  /**
   * Queues the given UIDMeta object for indexing
   * @param meta The meta data object to index
   * @return null
   */
  @Override
  public Deferred<Object> indexUIDMeta(final UIDMeta meta) {
    final StringBuilder uri = new StringBuilder("http://");
    uri.append(hosts.get(0).toHostString());
    uri.append("/").append(index).append("/").append(uidmeta_type).append("/");
    uri.append(meta.getType().toString()).append(meta.getUID());
    uri.append("?replication=async");
    
    final Request post = Request.Post(uri.toString())
      .bodyByteArray(JSON.serializeToBytes(meta));
    
    final Deferred<Object> result = new Deferred<Object>();
    async.execute(post, new AsyncCB(result));
    return result;
  }

  /**
   * Queues the given UIDMeta object for deletion
   * @param meta The meta data object to delete
   * @return null
   */
  public Deferred<Object> deleteUIDMeta(final UIDMeta meta) {
    final StringBuilder uri = new StringBuilder("http://");
    uri.append(hosts.get(0).toHostString());
    uri.append("/").append(index).append("/").append(tsmeta_type).append("/");
    uri.append(meta.getType().toString()).append(meta.getUID());
    uri.append("?replication=async");
    
    final Request delete = Request.Delete(uri.toString());
    
    final Deferred<Object> result = new Deferred<Object>();
    async.execute(delete, new AsyncCB(result));
    return result;
  }
  
  /**
   * Indexes an annotation object
   * <b>Note:</b> Unique Document ID = TSUID and Start Time
   * @param note The annotation to index
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).
   */
  public Deferred<Object> indexAnnotation(final Annotation note) {
    final StringBuilder uri = new StringBuilder("http://");
    uri.append(hosts.get(0).toHostString());
    uri.append("/").append(index).append("/").append(annotation_type).append("/");
    uri.append(note.getStartTime());
    if (note != null ) {
      uri.append(note.getTSUID());
    }
    uri.append("?replication=async");
    
    final Request post = Request.Post(uri.toString())
      .bodyByteArray(JSON.serializeToBytes(note));
    
    final Deferred<Object> result = new Deferred<Object>();
    async.execute(post, new AsyncCB(result));
    return result;
  }

  /**
   * Called to remove an annotation object from the index
   * <b>Note:</b> Unique Document ID = TSUID and Start Time
   * @param note The annotation to remove
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).
   */
  public Deferred<Object> deleteAnnotation(final Annotation note) {
    final StringBuilder uri = new StringBuilder("http://");
    uri.append(hosts.get(0).toHostString());
    uri.append("/").append(index).append("/").append(annotation_type).append("/");
    uri.append(note.getStartTime());
    if (note != null ) {
      uri.append(note.getTSUID());
    }
    
    final Request delete = Request.Delete(uri.toString());
    
    final Deferred<Object> result = new Deferred<Object>();
    async.execute(delete, new AsyncCB(result));
    return result;
  }
  
  public Deferred<SearchQuery> executeQuery(final SearchQuery query) {
    final Deferred<SearchQuery> result = new Deferred<SearchQuery>();
    
    final StringBuilder uri = new StringBuilder("http://");
    uri.append(hosts.get(0).toHostString());
    uri.append("/").append(index).append("/");
    switch(query.getType()) {
      case TSMETA:
      case TSMETA_SUMMARY:
      case TSUIDS:
        uri.append(tsmeta_type);
        break;
      case UIDMETA:
        uri.append(uidmeta_type);
        break;
      case ANNOTATION:
        uri.append(annotation_type);
        break;
    }
    uri.append("/_search");
    
    // setup the query body
    HashMap<String, Object> body = new HashMap<String, Object>(3);
    body.put("size", query.getLimit());
    body.put("from", query.getStartIndex());
    
    HashMap<String, Object> qs = new HashMap<String, Object>(1);
    body.put("query", qs);
    HashMap<String, String> query_string = new HashMap<String, String>(1);
    query_string.put("query", query.getQuery());
    qs.put("query_string", query_string);
    
    final Request request = Request.Post(uri.toString());
    request.bodyByteArray(JSON.serializeToBytes(body));
    
    final Async async = Async.newInstance().use(threadpool);
    async.execute(request, new SearchCB(query, result));
    return result;
  }
  
  /**
   * Gracefully closes connections
   */
  @Override
  public Deferred<Object> shutdown() {
    httpClient.getConnectionManager().shutdown();
    return null;
  }

  /** @return the version of this plugin */
  public String version() {
    return "2.0.0";
  }
  
  public void collectStats(final StatsCollector collector) {
    // do nothing for now
  }
  
  /**
   * Parses semicoln separated hosts from a config line into a host list. If
   * a given host includes a port, e.g. "host:port", the port will be parsed, 
   * otherwise port 9200 will be used.
   * @param config The config line to parse
   * @throws IllegalArgumentException if the line was empty or no hosts were 
   * parsed
   * @throws NumberFormatException if a parsed port can't be converted to an 
   * integer
   */
  private void setHosts(final String config) {
    if (config == null || config.isEmpty()) {
      throw new IllegalArgumentException("The hosts config was empty");
    }
    
    Builder<HttpHost> host_list = ImmutableList.<HttpHost>builder();
    String[] split_hosts = config.split(";");
    for (String host : split_hosts) {
      String[] host_split = host.split(":");
      int port = 9200;
      if (host_split.length > 1) {
        port = Integer.parseInt(host_split[1]);
      }
      host_list.add(new HttpHost(host_split[0], port));
    }
    this.hosts = host_list.build();
    if (this.hosts.size() < 1) {
      throw new IllegalArgumentException(
          "No hosts were found to load into the list");
    }
  }
  
  /**
   * Helper that loads config settings and throws exceptions if something is
   * amiss.
   * @throws IllegalArgumentException if a config value is invalid
   * @throws NumberFormatException if a config value is invalid
   */
  private void setConfiguration() {
    final String host_config = 
      config.getString("tsd.search.elasticsearch.hosts");
    if (host_config == null || host_config.isEmpty()) {
      throw new IllegalArgumentException("Missing search hosts configuration");
    }
    setHosts(host_config);

    // set index/types
    index = config.getString("tsd.search.elasticsearch.index");
    if (index == null || index.isEmpty()) {
      throw new IllegalArgumentException("Invalid index configuration value");
    }
    tsmeta_type = config.getString("tsd.search.elasticsearch.tsmeta_type");
    if (tsmeta_type == null || tsmeta_type.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid tsmeta_type configuration value");
    }    
    uidmeta_type = config.getString("tsd.search.elasticsearch.uidmeta_type");
    if (uidmeta_type == null || uidmeta_type.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid uidmeta_type configuration value");
    }
  }
  
  final class AsyncCB implements FutureCallback<Content> {

    final Deferred<Object> deferred;
    
    public AsyncCB(final Deferred<Object> deferred) {
      this.deferred = deferred;
    }
    
    @Override
    public void cancelled() {
      LOG.warn("Post was cancelled");
    }

    @Override
    public void completed(final Content content) {
      deferred.callback(true);
    }

    @Override
    public void failed(Exception e) {
      LOG.error("Post Exception", e);
    }
    
  }

  final class SearchCB implements FutureCallback<Content> {

    final SearchQuery query;
    final Deferred<SearchQuery> result;
    
    public SearchCB(final SearchQuery query, final Deferred<SearchQuery> result) {
      this.query = query;
      this.result = result;
    }
    
    @Override
    public void cancelled() {
      result.callback(null);
    }

    @Override
    public void completed(final Content content) {
      
      final JsonParser jp = JSON.parseToStream(content.asStream());
      if (jp == null) {
        LOG.warn("Query response was null or empty");
        result.callback(null);
        return;
      }
      
      try {
        JsonToken next = jp.nextToken();
        if (next != JsonToken.START_OBJECT) {
          LOG.error("Error: root should be object: quiting.");
          result.callback(null);
          return;
        }
      
        final List<Object> objects = new ArrayList<Object>();
        
        // loop through the JSON structure
        String parent = "";
        String last = "";
        
        while (jp.nextToken() != null) {
          String fieldName = jp.getCurrentName();
          if (fieldName != null)
            last = fieldName;
          
          if (jp.getCurrentToken() == JsonToken.START_ARRAY || 
              jp.getCurrentToken() == JsonToken.START_OBJECT)
            parent = last;
          
          if (fieldName != null && fieldName.equals("_source")) {
            if (jp.nextToken() == JsonToken.START_OBJECT) {
              // parse depending on type
              switch (query.getType()) {
                case TSMETA:
                case TSMETA_SUMMARY:
                case TSUIDS:
                  final TSMeta meta = jp.readValueAs(TSMeta.class);
                  if (query.getType() == SearchType.TSMETA) {
                    objects.add(meta);
                  } else if (query.getType() == SearchType.TSUIDS) {
                    objects.add(meta.getTSUID());
                  } else {
                    final HashMap<String, Object> map = 
                      new HashMap<String, Object>(3);
                    map.put("tsuid", meta.getTSUID());
                    map.put("metric", meta.getMetric().getName());
                    final HashMap<String, String> tags = 
                      new HashMap<String, String>(meta.getTags().size() / 2);
                    int idx = 0;
                    String name = "";
                    for (final UIDMeta uid : meta.getTags()) {
                      if (idx % 2 == 0) {
                        name = uid.getName();
                      } else {
                        tags.put(name, uid.getName());
                      }
                      idx++;
                    }
                    map.put("tags", tags);
                    objects.add(map);
                  }
                  break;
                case UIDMETA:
                  final UIDMeta uid = jp.readValueAs(UIDMeta.class);
                  objects.add(uid);
                  break;
                case ANNOTATION:
                  final Annotation note = jp.readValueAs(Annotation.class);
                  objects.add(note);
                  break;
              }
            }else
              LOG.warn("Invalid _source value from ES, should have been a START_OBJECT");
          } else if (fieldName != null && jp.getCurrentToken() != JsonToken.FIELD_NAME &&
              parent.equals("hits") && fieldName.equals("total")){
            LOG.trace("Total hits: [" + jp.getValueAsInt() + "]");
            query.setTotalResults(jp.getValueAsInt());
          } else if (fieldName != null && jp.getCurrentToken() != JsonToken.FIELD_NAME &&
              fieldName.equals("took")){
            LOG.trace("Time taken: [" + jp.getValueAsInt() + "]");
            query.setTime(jp.getValueAsInt());
          }
          
          query.setResults(objects);
        }
        
        result.callback(query);
        
      } catch (JsonParseException e) {
        LOG.error("Query failed", e);
        throw new RuntimeException(e);
      } catch (IOException e) {
        LOG.error("Query failed", e);
        throw new RuntimeException(e);
      }
    }

    @Override
    public void failed(final Exception e) {
      LOG.error("Query failed", e);
      throw new RuntimeException(e);
    }
    
  }
}
