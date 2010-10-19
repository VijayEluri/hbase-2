/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.handler;

import java.io.IOException;
import java.util.Map;
import java.util.NavigableMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.HServerInfo;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.executor.EventHandler;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.master.DeadServer;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.zookeeper.KeeperException;

/**
 * Process server shutdown.
 * Server-to-handle must be already in the deadservers lists.  See
 * {@link ServerManager#expireServer(HServerInfo)}.
 */
public class ServerShutdownHandler extends EventHandler {
  private static final Log LOG = LogFactory.getLog(ServerShutdownHandler.class);
  private final HServerInfo hsi;
  private final Server server;
  private final MasterServices services;
  private final DeadServer deadServers;

  public ServerShutdownHandler(final Server server, final MasterServices services,
      final DeadServer deadServers, final HServerInfo hsi) {
    super(server, EventType.M_SERVER_SHUTDOWN);
    this.hsi = hsi;
    this.server = server;
    this.services = services;
    this.deadServers = deadServers;
    if (!this.deadServers.contains(hsi.getServerName())) {
      LOG.warn(hsi.getServerName() + " is NOT in deadservers; it should be!");
    }
  }

  @Override
  public void process() throws IOException {
    Pair<Boolean, Boolean> carryingCatalog = null;
    try {
      carryingCatalog =
        this.server.getCatalogTracker().processServerShutdown(this.hsi);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", e);
    } catch (KeeperException e) {
      this.server.abort("In server shutdown processing", e);
      throw new IOException("Aborting", e);
    }
    final String serverName = this.hsi.getServerName();

    LOG.info("Splitting logs for " + serverName);
    this.services.getMasterFileSystem().splitLog(serverName);

    // Clean out anything in regions in transition.  Being conservative and
    // doing after log splitting.  Could do some states before -- OPENING?
    // OFFLINE? -- and then others after like CLOSING that depend on log
    // splitting.
    this.services.getAssignmentManager().processServerShutdown(this.hsi);

    // Assign root and meta if we were carrying them.
    if (carryingCatalog.getFirst()) { // -ROOT-
      try {
        this.services.getAssignmentManager().assignRoot();
      } catch (KeeperException e) {
        this.server.abort("In server shutdown processing, assigning root", e);
        throw new IOException("Aborting", e);
      }
    }
    if (carryingCatalog.getSecond()) { // .META.
      this.services.getAssignmentManager().assignMeta();
    }

    // Wait on meta to come online; we need it to progress.
    try {
      this.server.getCatalogTracker().waitForMeta();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", e);
    }

    NavigableMap<HRegionInfo, Result> hris =
      MetaReader.getServerUserRegions(this.server.getCatalogTracker(), this.hsi);
    LOG.info("Reassigning the " + hris.size() + " region(s) that " + serverName +
      " was carrying");

    // We should encounter -ROOT- and .META. first in the Set given how its
    // a sorted set.
    for (Map.Entry<HRegionInfo, Result> e: hris.entrySet()) {
      processDeadRegion(e.getKey(), e.getValue(),
          this.services.getAssignmentManager(),
          this.server.getCatalogTracker());
      this.services.getAssignmentManager().assign(e.getKey());
    }
    this.deadServers.remove(serverName);
    LOG.info("Finished processing of shutdown of " + serverName);
  }

  public static void processDeadRegion(HRegionInfo hri, Result result,
      AssignmentManager assignmentManager, CatalogTracker catalogTracker)
  throws IOException {
    // If table is not disabled but the region is offlined,
    boolean disabled = assignmentManager.isTableDisabled(
        hri.getTableDesc().getNameAsString());
    if (disabled) return;
    if (hri.isOffline() && hri.isSplit()) {
      fixupDaughters(result, assignmentManager, catalogTracker);
      return;
    }
  }

  /**
   * Check that daughter regions are up in .META. and if not, add them.
   * @param hris All regions for this server in meta.
   * @param result The contents of the parent row in .META.
   * @throws IOException
   */
  static void fixupDaughters(final Result result,
      final AssignmentManager assignmentManager,
      final CatalogTracker catalogTracker) throws IOException {
    fixupDaughter(result, HConstants.SPLITA_QUALIFIER, assignmentManager,
        catalogTracker);
    fixupDaughter(result, HConstants.SPLITB_QUALIFIER, assignmentManager,
        catalogTracker);
  }

  /**
   * Check individual daughter is up in .META.; fixup if its not.
   * @param result The contents of the parent row in .META.
   * @param qualifier Which daughter to check for.
   * @throws IOException
   */
  static void fixupDaughter(final Result result, final byte [] qualifier,
      final AssignmentManager assignmentManager,
      final CatalogTracker catalogTracker)
  throws IOException {
    byte [] bytes = result.getValue(HConstants.CATALOG_FAMILY, qualifier);
    if (bytes == null || bytes.length <= 0) return;
    HRegionInfo hri = Writables.getHRegionInfo(bytes);
    Pair<HRegionInfo, HServerAddress> pair =
      MetaReader.getRegion(catalogTracker, hri.getRegionName());
    if (pair == null || pair.getFirst() == null) {
      LOG.info("Fixup; missing daughter " + hri.getEncodedName());
      MetaEditor.addDaughter(catalogTracker, hri, null);
      assignmentManager.assign(hri);
    }
  }
}
