// This file is part of OpenTSDB.
// Copyright (C) 2018  The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.query.plan;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.google.common.graph.Traverser;
import com.google.common.hash.HashCode;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import net.opentsdb.common.Const;
import net.opentsdb.configuration.Configuration;
import net.opentsdb.data.TimeSeriesDataSource;
import net.opentsdb.data.TimeSeriesDataSourceFactory;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryNodeConfig;
import net.opentsdb.query.QueryNodeFactory;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.TimeSeriesDataSourceConfig;
import net.opentsdb.query.serdes.SerdesOptions;
import net.opentsdb.stats.Span;
import net.opentsdb.utils.Deferreds;

/**
 * A query planner that handles push-down operations to data sources.
 * 
 * TODO - more work and break it into an interface like the old one.
 * 
 * @since 3.0
 */
public class DefaultQueryPlanner implements QueryPlanner {
  private static final Logger LOG = LoggerFactory.getLogger(
      DefaultQueryPlanner.class);
  
  /** The context we belong to. We get the query here. */
  protected final QueryPipelineContext context;

  /** The pass-through context sink node. */
  protected final QueryNode context_sink;
  
  /** A reference to the sink config. */
  protected final ContextNodeConfig context_sink_config;
  
  protected final Map<String, String> sink_filter;
  
  /** The roots (sent to sinks) of the user given graph. */
  protected List<QueryNodeConfig> roots;
  
  /** The planned execution graph. */
  protected MutableGraph<QueryNode> graph;
  
  /** The list of data sources we're fetching from. */
  protected List<TimeSeriesDataSource> data_sources;
  
  /** The set of data source config nodes. */
  protected final Set<QueryNodeConfig> source_nodes;
  
  /** The configuration graph. */
  protected MutableGraph<QueryNodeConfig> config_graph;
  
  /** Map of the config IDs to nodes for use in linking and unit testing. */
  protected final Map<String, QueryNode> nodes_map;
  
  /** The context node from the query pipeline context. All results pass
   * through this. */
  protected QueryNodeConfig context_node;
  
  /** The set of QueryResult objects we should see. */
  protected Set<String> serialization_sources;
  
  /**
   * Default ctor.
   * @param context The non-null context to pull the query from.
   * @param context_sink The non-null context pass-through node.
   */
  public DefaultQueryPlanner(final QueryPipelineContext context,
                             final QueryNode context_sink) {
    this.context = context;
    this.context_sink = context_sink;
    sink_filter = Maps.newHashMap();
    roots = Lists.newArrayList();
    data_sources = Lists.newArrayList();
    nodes_map = Maps.newHashMap();
    context_sink_config = new ContextNodeConfig();
    source_nodes = Sets.newHashSet();
    config_graph = GraphBuilder.directed()
        .allowsSelfLoops(false)
        .build();
    
    if (context.query().getSerdesConfigs() != null) {
      for (final SerdesOptions config : context.query().getSerdesConfigs()) {
        if (config.getFilter() != null) {
          for (final String filter : config.getFilter()) {
            // Note: Assuming input validation here, that one or either
            // side is not null and includes a proper node Id.
            final String[] split = filter.split(":");
            if (split.length == 2) {
              sink_filter.put(split[0], split[1]);
            } else if (split.length == 1) {
              sink_filter.put(split[0], null);
            } else {
              throw new RuntimeException("WTF?? Invalid filter: " + filter);
            }
          }
        }
      }
    }
  }
  
  /**
   * Does the hard work.
   */
  public Deferred<Void> plan(final Span span) {
    final Map<String, QueryNodeConfig> config_map = 
        Maps.newHashMapWithExpectedSize(
            context.query().getExecutionGraph().size());
    context_node = context_sink_config;
    config_graph.addNode(context_node);
    config_map.put("QueryContext", context_node);
    
    // the first step is to add the vertices to the graph and we'll stash
    // the nodes in a map by node ID so we can link them later.
    for (final QueryNodeConfig node : context.query().getExecutionGraph()) {
      if (config_map.putIfAbsent(node.getId(), node) != null) {
        throw new IllegalArgumentException("The node id \"" 
            + node.getId() + "\" appeared more than once in the "
            + "graph. It must be unique.");
      }
      config_graph.addNode(node);
    }
    
    // now link em with the edges.
    for (final QueryNodeConfig node : context.query().getExecutionGraph()) {
      if (node instanceof TimeSeriesDataSourceConfig) {
        source_nodes.add(node);
      }
      
      if (node.getSources() != null) {
        for (final String source : node.getSources()) {
          config_graph.putEdge(node, config_map.get(source));
          if (Graphs.hasCycle(config_graph)) {
            throw new IllegalArgumentException("Cycle found linking node " 
                + node.getId() + " to " + config_map.get(source).getId());
          }
        }
      }
    }
    
    final Map<String, QueryNodeFactory> factory_cache = Maps.newHashMap();
    final Set<String> satisfied_filters = Sets.newHashSet();
    
    // next we walk and let the factories update the graph as needed.
    // Note the clone to avoid concurrent modification of the graph.
    Set<QueryNodeConfig> already_setup = Sets.newHashSet();
    boolean modified = true;
    while (modified) {
      if (source_nodes.isEmpty()) {
        break;
      }
      for (final QueryNodeConfig node : source_nodes) {
        modified = recursiveSetup(node, already_setup, factory_cache, 
            satisfied_filters);
        if (modified) {
          break;
        }
      }
    }
    
    class ConfigInitCB implements Callback<Deferred<Void>, Void> {

      @Override
      public Deferred<Void> call(final Void ignored) throws Exception {
        // before doing any more work, make sure the the filters have been
        // satisfied.
        for (final String key : sink_filter.keySet()) {
          if (!satisfied_filters.contains(key)) {
            throw new IllegalArgumentException("Unsatisfied sink filter: " + key);
          }
        }
        
        // next, push down by walking up from the data sources.
        final List<QueryNodeConfig> copy = Lists.newArrayList(source_nodes);
        for (final QueryNodeConfig node : copy) {
          final QueryNodeFactory factory;
          if (node instanceof TimeSeriesDataSourceConfig) {
            final String source_id =  
                Strings.isNullOrEmpty(((TimeSeriesDataSourceConfig) node)
                    .getSourceId()) ? null : 
                      ((TimeSeriesDataSourceConfig) node)
                        .getSourceId().toLowerCase();
            factory = getFactory(source_id, factory_cache);
          } else if (!Strings.isNullOrEmpty(node.getType())) {
            factory = getFactory(node.getType().toLowerCase(), factory_cache);
          } else {
            factory = getFactory(node.getId().toLowerCase(), factory_cache);
          }
          
          // TODO - cleanup the source factories. ugg!!!
          if (factory == null || !(factory instanceof TimeSeriesDataSourceFactory)) {
            throw new IllegalArgumentException("No node factory found for "
                + "configuration " + node + "  Factory=" + factory);
          }
          
          final List<QueryNodeConfig> push_downs = Lists.newArrayList();
          MutableGraph<QueryNodeConfig> clone = Graphs.copyOf(config_graph);
          for (final QueryNodeConfig n : clone.predecessors(node)) {
            final boolean pushed = pushDown(
                node, 
                node, 
                (TimeSeriesDataSourceFactory) factory, 
                n, 
                push_downs,
                clone);
            if (pushed) {
              config_graph.removeEdge(n, node);
              push_downs.add(n);
            }
            
            if (n != context_sink_config && config_graph.successors(n).isEmpty()) {
              config_graph.removeNode(n);
            }
          }
          
          if (!push_downs.isEmpty()) {
            // now dump the push downs into this node.
            final TimeSeriesDataSourceConfig new_config = 
                ((TimeSeriesDataSourceConfig.Builder) node.toBuilder())
                .setPushDownNodes(push_downs)
                .build();
            replace(node, new_config);
          }
        }
        
        // TODO clean out nodes that won't contribute to serialization.
        
        // compute source IDs.
        serialization_sources = computeSerializationSources(context_node);
        
        // now go and build the node graph
        graph = GraphBuilder.directed()
            .allowsSelfLoops(false)
            .build();//new DirectedAcyclicGraph<QueryNode, DefaultEdge>(DefaultEdge.class);
        graph.addNode(context_sink);
        nodes_map.put(context_sink_config.getId(), context_sink);
        
        final List<Long> constructed = Lists.newArrayList();
        Traverser<QueryNodeConfig> traverser = Traverser.forGraph(config_graph);
        for (final QueryNodeConfig node : traverser.breadthFirst(context_node)) {
          if (config_graph.predecessors(node).isEmpty()) {
            buildNodeGraph(context, node, constructed, nodes_map, factory_cache);
          }
        }
        
        // depth first initiation of the executors since we have to init
        // the ones without any downstream dependencies first.
        Set<QueryNode> initialized = Sets.newHashSet();
        return recursiveInit(context_sink, initialized, span);
      }
      
    }
    
    // one more iteration to make sure we capture all the source nodes
    // from the graph setup.
    source_nodes.clear();
    for (final QueryNodeConfig node : config_graph.nodes()) {
      if (node instanceof TimeSeriesDataSourceConfig) {
        source_nodes.add(node);
      }
    }
    
    final List<Deferred<Void>> deferreds = 
        Lists.newArrayListWithExpectedSize(source_nodes.size());
    for (final QueryNodeConfig c : source_nodes) {
      if (((TimeSeriesDataSourceConfig) c).getFilter() != null) {
        deferreds.add(((TimeSeriesDataSourceConfig) c)
            .getFilter().initialize(span));
      }
    }
    
    return Deferred.group(deferreds)
        .addCallback(Deferreds.VOID_GROUP_CB)
        .addCallbackDeferring(new ConfigInitCB());
  }
  
  /**
   * Recursive setup that will stop and allow the loop to restart setup
   * if the graph has changed.
   * @param node The non-null current node.
   * @param already_setup Nodes already setup to avoid repeats.
   * @param factory_cache The factory cache.
   * @param satisfied_filters Filters.
   * @return true if the graph has mutated and we should restart, false
   * if not.
   */
  private boolean recursiveSetup(
      final QueryNodeConfig node, 
      final Set<QueryNodeConfig> already_setup, 
      final Map<String, QueryNodeFactory> factory_cache,
      final Set<String> satisfied_filters) {
    if (!already_setup.contains(node) && node != context_sink_config) {
      // TODO - ugg!! There must be a better way to determine if the graph
      // has been modified.
      final MutableGraph<QueryNodeConfig> clone = Graphs.copyOf(config_graph);
      
      final Set<QueryNodeConfig> incoming = config_graph.predecessors(node);
      if (incoming.isEmpty()) {
        if (sink_filter.isEmpty()) {
          config_graph.putEdge(context_node, node);
          if (Graphs.hasCycle(config_graph)) {
            throw new IllegalArgumentException("Cycle found linking node " 
                + context_node.getId() + " to " + node.getId());
          }
        } else {
          roots.add(node);
        }
      }
      
      if (sink_filter.containsKey(node.getId())) {
        final String source = sink_filter.get(node.getId());
        if (source != null) {
          // TODO - make sure this links to the source, otherwise skip it.
          config_graph.putEdge(context_node, node);
          if (Graphs.hasCycle(config_graph)) {
            throw new IllegalArgumentException("Cycle found linking node " 
                + context_node.getId() + " to " + node.getId());
          }
          satisfied_filters.add(node.getId());
        } else {
          // we want the link.
          config_graph.putEdge(context_node, node);
          if (Graphs.hasCycle(config_graph)) {
            throw new IllegalArgumentException("Cycle found linking node " 
                + context_node.getId() + " to " + node.getId());
          }
          satisfied_filters.add(node.getId());
        }
      }
      
      final String factory_id;
      if (node instanceof TimeSeriesDataSourceConfig) {
        factory_id = Strings.isNullOrEmpty(((TimeSeriesDataSourceConfig) node)
            .getSourceId()) ? null : 
              ((TimeSeriesDataSourceConfig) node)
              .getSourceId().toLowerCase();
        final TimeSeriesDataSourceFactory factory = 
            (TimeSeriesDataSourceFactory) getFactory(factory_id, factory_cache);
        if (factory == null) {
          throw new IllegalArgumentException("No data source factory found for: " 
              + factory_id);
        }
        factory.setupGraph(context.query(), node, this);
        factory_cache.put(factory_id, factory);
      } else {
        if (!Strings.isNullOrEmpty(node.getType())) {
          factory_id = node.getType().toLowerCase();
        } else {
          factory_id = node.getId().toLowerCase();
        }
        
        final QueryNodeFactory factory = getFactory(factory_id, factory_cache);
        if (factory == null) {
          throw new IllegalArgumentException("No node factory found for: " 
              + factory_id);
        }
        factory.setupGraph(context.query(), node, this);
        factory_cache.put(factory_id, factory);
      }
      
      already_setup.add(node);
      if (!config_graph.equals(clone)) {
        return true;
      }
    }
    
    // all done, move up.
    for (final QueryNodeConfig upstream : config_graph.predecessors(node)) {
      if (recursiveSetup(upstream, already_setup, factory_cache, satisfied_filters)) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Helper to DFS initialize the nodes.
   * @param node The non-null current node.
   * @param initialized A set of already initialized nodes.
   * @param span An optioanl tracing span.
   * @return A deferred resolving to null or an exception.
   */
  private Deferred<Void> recursiveInit(final QueryNode node, 
                                       final Set<QueryNode> initialized, 
                                       final Span span) {
    if (initialized.contains(node)) {
      return Deferred.fromResult(null);
    }
    
    final Set<QueryNode> successors = graph.successors(node);
    if (successors.isEmpty()) {
      initialized.add(node);
      return node.initialize(span);
    }
    
    List<Deferred<Void>> deferreds = Lists.newArrayListWithExpectedSize(successors.size());
    for (final QueryNode successor : successors) {
      deferreds.add(recursiveInit(successor, initialized, span));
    }
    
    class InitCB implements Callback<Deferred<Void>, Void> {
      @Override
      public Deferred<Void> call(final Void ignored) throws Exception {
        initialized.add(node);
        if (node == context_sink) {
          return Deferred.fromResult(null);
        }
        return node.initialize(span);
      }
    }
    
    return Deferred.group(deferreds)
        .addCallback(Deferreds.VOID_GROUP_CB)
        .addCallbackDeferring(new InitCB());
  }
  
  /**
   * Recursive method extract 
   * @param parent The parent of this node.
   * @param source The data source node.
   * @param factory The data source factory.
   * @param node The current node.
   * @param push_downs The non-null list of node configs that we'll 
   * populate any time we can push down.
   * @param clone The clone to work from while mutating.
   * @return An edge to link with if the previous node was pushed down.
   */
  private boolean pushDown(
      final QueryNodeConfig parent,
      final QueryNodeConfig source, 
      final TimeSeriesDataSourceFactory factory, 
      final QueryNodeConfig node,
      final List<QueryNodeConfig> push_downs,
      final MutableGraph<QueryNodeConfig> clone) {
    if (!factory.supportsPushdown(node.getClass())) {
      if (!config_graph.hasEdgeConnecting(node, parent)) {
        config_graph.putEdge(node, parent);
        if (Graphs.hasCycle(config_graph)) {
          throw new IllegalArgumentException("Cycle found linking node " 
              + node.getId() + " to " + parent.getId());
        }
      }
      return false;
    }
    
    if (!node.pushDown()) {
      // reached a node config that doesn't allow push downs.
      return false;
    }
    
    // see if we can walk up for more
    final Set<QueryNodeConfig> incoming = clone.predecessors(node);
    if (!incoming.isEmpty()) {
      List<QueryNodeConfig> nodes = Lists.newArrayList();
      for (final QueryNodeConfig n : incoming) {
        nodes.add(n);
        boolean pushed = pushDown(parent, node, factory, n, push_downs, clone);
        if (pushed) {
          config_graph.removeEdge(n, node);
        }
      }
      
      for (final QueryNodeConfig n : nodes) {
        if (clone.successors(n).isEmpty()) {
          if (config_graph.removeNode(n)) {
            push_downs.add(n);
          }
        }
      }
    }
    
    // purge if we pushed everything down
    if (config_graph.successors(node).isEmpty()) {
      if (config_graph.removeNode(node)) {
        push_downs.add(node);
      }
    }
    
    return true;
  }

  /**
   * Resursive helper to build and link the actual node graph.
   * @param context The non-null context we're working with.
   * @param node The current node config.
   * @param constructed A cache to determine if we've already instantated
   * and linked the node.
   * @param nodes_map A map of instantiated nodes to use for linking.
   * @param factory_cache The cache of factories so we don't have to keep
   * looking them up.
   * @return A node to link with.
   */
  private QueryNode buildNodeGraph(
      final QueryPipelineContext context, 
      final QueryNodeConfig node, 
      final List<Long> constructed,
      final Map<String, QueryNode> nodes_map,
      final Map<String, QueryNodeFactory> factory_cache) {
    // short circuit initialized nodes.
    if (constructed.contains(node.buildHashCode().asLong())) {
      return nodes_map.get(node.getId());
    }
    
    // walk up the graph.
    final List<QueryNode> sources = Lists.newArrayList();
    for (final QueryNodeConfig n : config_graph.successors(node)) {
      sources.add(buildNodeGraph(
          context, 
          n, 
          constructed, 
          nodes_map, 
          factory_cache));
    }
    
    // special case, ug.
    if (node instanceof ContextNodeConfig) {
      for (final QueryNode source_node : sources) {
          graph.putEdge(context_sink, source_node);
          if (Graphs.hasCycle(graph)) {
            throw new IllegalArgumentException("!TF?");
          }
      }
      return context_sink;
    }
    
    QueryNodeFactory factory;
    if (node instanceof TimeSeriesDataSourceConfig) {
      factory = getFactory(
          Strings.isNullOrEmpty(((TimeSeriesDataSourceConfig) node)
              .getSourceId()) ? null : 
                ((TimeSeriesDataSourceConfig) node)
                .getSourceId().toLowerCase(),
          factory_cache);
    } else if (!Strings.isNullOrEmpty(node.getType())) {
      factory = getFactory(node.getType().toLowerCase(), factory_cache);
    } else {
      factory = getFactory(node.getId().toLowerCase(), factory_cache);
    }
    if (factory == null) {
      throw new IllegalArgumentException("No node factory found for "
          + "configuration " + node);
    }
    
    QueryNode query_node = factory.newNode(context, node);
    if (query_node == null) {
      throw new IllegalStateException("Factory returned a null "
          + "instance for " + node);
    }
    graph.addNode(query_node);
    nodes_map.put(query_node.config().getId(), query_node);
    constructed.add(node.buildHashCode().asLong());
    
    if (query_node instanceof TimeSeriesDataSource) {
      data_sources.add((TimeSeriesDataSource) query_node);
    }
    
    for (final QueryNode source_node : sources) {
      graph.putEdge(query_node, source_node);
      if (Graphs.hasCycle(graph)) {
        throw new IllegalArgumentException("WTF??");
      }
    }
    
    return query_node;
  }
  
  /** @return The non-null node graph. */
  public MutableGraph<QueryNode> graph() {
    return graph;
  }
  
  public MutableGraph<QueryNodeConfig> configGraph() {
    return config_graph;
  }
  
  @Override
  public QueryPipelineContext context() {
    return context;
  }
  
  /** @return The non-null data sources list. */
  public List<TimeSeriesDataSource> sources() {
    return data_sources;
  }

  /** @return The non-null list of result IDs to watch for. */
  public Set<String> serializationSources() {
    return serialization_sources;
  }
  
  @Override
  public QueryNode nodeForId(final String id) {
    return nodes_map.get(id);
  }
  
  /**
   * Recursive function that calculates the IDs that we should see 
   * emitted through the pipeline as QueryResult objects.
   * TODO - this assumes one result per data source.
   * @param node The non-null node to work from.
   * @return A set of unique results we should see.
   */
  private Set<String> computeSerializationSources(
      final QueryNodeConfig node) {
    if (node instanceof TimeSeriesDataSourceConfig ||
        node.joins()) {
      return Sets.newHashSet(node.getId());
    }
    
    final Set<String> ids = Sets.newHashSet();
    for (final QueryNodeConfig downstream : config_graph.successors(node)) {
      final Set<String> downstream_ids = computeSerializationSources(downstream);
      if (node == context_node) {
        // prepend
        if (downstream instanceof TimeSeriesDataSourceConfig ||
            downstream.joins()) {
          ids.addAll(downstream_ids);
        } else {
          for (final String id : downstream_ids) {
            ids.add(downstream.getId() + ":" + id);
          }
        }
      } else {
        ids.addAll(downstream_ids);
      }
    }
    return ids;
  }
  
  /**
   * TODO - look at this to find a better way than having a generic
   * config.
   */
  class ContextNodeConfig implements QueryNodeConfig {

    @Override
    public int compareTo(QueryNodeConfig o) {
      // TODO Auto-generated method stub
      return 0;
    }

    @Override
    public String getId() {
      return "QueryContext";
    }
    
    @Override
    public String getType() {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public List<String> getSources() {
      // TODO Auto-generated method stub
      return null;
    }
    
    @Override
    public HashCode buildHashCode() {
      // TODO Auto-generated method stub
      return Const.HASH_FUNCTION().newHasher()
          .putInt(System.identityHashCode(this)) // TEMP!
          .hash();
    }

    @Override
    public boolean pushDown() {
      // TODO Auto-generated method stub
      return false;
    }

    @Override
    public boolean joins() {
      // TODO Auto-generated method stub
      return false;
    }

    @Override
    public Map<String, String> getOverrides() {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public String getString(Configuration config, String key) {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public int getInt(Configuration config, String key) {
      // TODO Auto-generated method stub
      return 0;
    }

    @Override
    public long getLong(Configuration config, String key) {
      // TODO Auto-generated method stub
      return 0;
    }

    @Override
    public boolean getBoolean(Configuration config, String key) {
      // TODO Auto-generated method stub
      return false;
    }

    @Override
    public double getDouble(Configuration config, String key) {
      // TODO Auto-generated method stub
      return 0;
    }

    @Override
    public boolean hasKey(String key) {
      // TODO Auto-generated method stub
      return false;
    }

    @Override
    public Builder toBuilder() {
      // TODO Auto-generated method stub
      return null;
    }
  }

  /**
   * Helper to replace a node with a new one, moving edges.
   * @param old_config The non-null old node that is present in the graph.
   * @param new_config The non-null new node that is not present in the graph.
   * @param graph The non-null graph to mutate.
   */
  public void replace(final QueryNodeConfig old_config,
                      final QueryNodeConfig new_config) {
    final List<QueryNodeConfig> upstream = Lists.newArrayList();
    for (final QueryNodeConfig n : config_graph.predecessors(old_config)) {
      upstream.add(n);
    }
    for (final QueryNodeConfig n : upstream) {
      config_graph.removeEdge(n, old_config);
    }
    
    final List<QueryNodeConfig> downstream = Lists.newArrayList();
    for (final QueryNodeConfig n : config_graph.successors(old_config)) {
      downstream.add(n);
    }
    for (final QueryNodeConfig n : downstream) {
      config_graph.removeEdge(old_config, n);
    }
    
    config_graph.removeNode(old_config);
    config_graph.addNode(new_config);
    
    if (old_config instanceof TimeSeriesDataSourceConfig && 
        source_nodes.contains(old_config)) {
      source_nodes.remove(old_config);
    }
    
    if (new_config instanceof TimeSeriesDataSourceConfig) {
      source_nodes.add(new_config);
    }
    
    for (final QueryNodeConfig up : upstream) {
      config_graph.putEdge(up, new_config);
      if (Graphs.hasCycle(config_graph)) {
        throw new IllegalArgumentException("Cycle found linking node " 
            + up.getId() + " to " + new_config.getId());
      }
    }
    
    for (final QueryNodeConfig down : downstream) {
      config_graph.putEdge(new_config, down);
      if (Graphs.hasCycle(config_graph)) {
        throw new IllegalArgumentException("Cycle found linking node " 
            + new_config.getId() + " to " + down.getId());
      }
    }
  }

  @Override
  public boolean addEdge(final QueryNodeConfig from, 
                        final QueryNodeConfig to) {
    final boolean added = config_graph.putEdge(from, to);
    if (Graphs.hasCycle(config_graph)) {
      throw new IllegalArgumentException("Cycle found linking node " 
          + from.getId() + " to " + to.getId()); 
    }
    
    if (from instanceof TimeSeriesDataSourceConfig) {
      source_nodes.add(from);
    }
    if (to instanceof TimeSeriesDataSourceConfig) {
      source_nodes.add(to);
    }
    return added;
  }

  @Override
  public boolean removeEdge(final QueryNodeConfig from, 
                            final QueryNodeConfig to) {
    if (config_graph.removeEdge(from, to)) {
      if (config_graph.predecessors(from).isEmpty() && 
          config_graph.successors(from).isEmpty()) {
        config_graph.removeNode(from);
        if (from instanceof TimeSeriesDataSourceConfig) {
          source_nodes.remove(from);
        }
      }
      
      if (config_graph.predecessors(to).isEmpty() && 
          config_graph.successors(to).isEmpty()) {
        config_graph.removeNode(to);
        if (to instanceof TimeSeriesDataSourceConfig) {
          source_nodes.remove(to);
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public boolean removeNode(final QueryNodeConfig config) {
    if (config_graph.removeNode(config)) {
      if (config instanceof TimeSeriesDataSourceConfig) {
        source_nodes.remove(config);
      }
      return true;
    }
    return false;
  }
  
  /**
   * Helper to get the factory from the cache or registry.
   * @param key The key, may be null for the default.
   * @param factory_cache A non-null factory cache.
   * @return
   */
  QueryNodeFactory getFactory(final String key, 
                              final Map<String, QueryNodeFactory> factory_cache) {
    QueryNodeFactory factory = factory_cache.get(key);
    if (factory != null) {
      return factory;
    }
    factory = context.tsdb().getRegistry().getQueryNodeFactory(key);
    if (factory != null) {
      factory_cache.put(key, factory);
    }
    return factory;
  }
  
  /**
   * Helper for UTs and debugging to print the graph.
   */
  public void printConfigGraph() {
    System.out.println(" ------------------------- ");
    for (final QueryNodeConfig node : config_graph.nodes()) {
      System.out.println("[V] " + node.getId() + " (" + node.getClass().getSimpleName() + ")");
    }
    System.out.println();
    for (final EndpointPair<QueryNodeConfig> pair : config_graph.edges()) {
      System.out.println("[E] " + pair.nodeU().getId() + " => " + pair.nodeV().getId());
    }
    System.out.println(" ------------------------- ");
  }
  
}