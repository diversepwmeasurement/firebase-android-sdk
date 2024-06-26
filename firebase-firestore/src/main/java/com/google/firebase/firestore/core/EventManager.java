// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.core;

import static com.google.firebase.firestore.util.Assert.hardAssert;

import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.ListenSource;
import com.google.firebase.firestore.core.SyncEngine.SyncEngineCallback;
import com.google.firebase.firestore.util.Util;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EventManager is responsible for mapping queries to query event listeners. It handles "fan-out."
 * (Identical queries will re-use the same watch on the backend.)
 */
public final class EventManager implements SyncEngineCallback {

  private static class QueryListenersInfo {
    private final List<QueryListener> listeners;
    private ViewSnapshot viewSnapshot;
    private int targetId;

    QueryListenersInfo() {
      listeners = new ArrayList<>();
    }

    // Helper methods that checks if the query has listeners that listening to remote store
    boolean hasRemoteListeners() {
      for (QueryListener listener : listeners) {
        if (listener.listensToRemoteStore()) {
          return true;
        }
      }
      return false;
    }
  }

  /** Holds (internal) options for listening */
  public static class ListenOptions {
    /** Raise events when only metadata of documents changes */
    public boolean includeDocumentMetadataChanges;

    /** Raise events when only metadata of the query changes */
    public boolean includeQueryMetadataChanges;

    /** Wait for a sync with the server when online, but still raise events while offline. */
    public boolean waitForSyncWhenOnline;

    /** Sets the source the query listens to. */
    public ListenSource source = ListenSource.DEFAULT;
  }

  private final SyncEngine syncEngine;

  private final Map<Query, QueryListenersInfo> queries;

  private final Set<EventListener<Void>> snapshotsInSyncListeners = new HashSet<>();

  private OnlineState onlineState = OnlineState.UNKNOWN;

  public EventManager(SyncEngine syncEngine) {
    this.syncEngine = syncEngine;
    queries = new HashMap<>();
    syncEngine.setCallback(this);
  }

  private enum ListenerSetupAction {
    INITIALIZE_LOCAL_LISTEN_AND_REQUIRE_WATCH_CONNECTION,
    INITIALIZE_LOCAL_LISTEN_ONLY,
    REQUIRE_WATCH_CONNECTION_ONLY,
    NO_ACTION_REQUIRED
  }

  private enum ListenerRemovalAction {
    TERMINATE_LOCAL_LISTEN_AND_REQUIRE_WATCH_DISCONNECTION,
    TERMINATE_LOCAL_LISTEN_ONLY,
    REQUIRE_WATCH_DISCONNECTION_ONLY,
    NO_ACTION_REQUIRED
  }

  /**
   * Adds a query listener that will be called with new snapshots for the query. The EventManager is
   * responsible for multiplexing many listeners to a single listen in the SyncEngine and will
   * perform a listen if it's the first QueryListener added for a query.
   *
   * @return the targetId of the listen call in the SyncEngine.
   */
  public int addQueryListener(QueryListener queryListener) {
    Query query = queryListener.getQuery();
    ListenerSetupAction listenerAction = ListenerSetupAction.NO_ACTION_REQUIRED;

    QueryListenersInfo queryInfo = queries.get(query);
    if (queryInfo == null) {
      queryInfo = new QueryListenersInfo();
      queries.put(query, queryInfo);
      listenerAction =
          queryListener.listensToRemoteStore()
              ? ListenerSetupAction.INITIALIZE_LOCAL_LISTEN_AND_REQUIRE_WATCH_CONNECTION
              : ListenerSetupAction.INITIALIZE_LOCAL_LISTEN_ONLY;
    } else if (!queryInfo.hasRemoteListeners() && queryListener.listensToRemoteStore()) {
      // Query has been listening to local cache, and tries to add a new listener sourced from
      // watch.
      listenerAction = ListenerSetupAction.REQUIRE_WATCH_CONNECTION_ONLY;
    }

    queryInfo.listeners.add(queryListener);

    // Run global snapshot listeners if a consistent snapshot has been emitted.
    boolean raisedEvent = queryListener.onOnlineStateChanged(onlineState);
    hardAssert(
        !raisedEvent, "onOnlineStateChanged() shouldn't raise an event for brand-new listeners.");

    if (queryInfo.viewSnapshot != null) {
      raisedEvent = queryListener.onViewSnapshot(queryInfo.viewSnapshot);
      if (raisedEvent) {
        raiseSnapshotsInSyncEvent();
      }
    }

    switch (listenerAction) {
      case INITIALIZE_LOCAL_LISTEN_AND_REQUIRE_WATCH_CONNECTION:
        queryInfo.targetId =
            syncEngine.listen(
                query,
                /** shouldListenToRemote= */
                true);
        break;
      case INITIALIZE_LOCAL_LISTEN_ONLY:
        queryInfo.targetId =
            syncEngine.listen(
                query,
                /** shouldListenToRemote= */
                false);
        break;
      case REQUIRE_WATCH_CONNECTION_ONLY:
        syncEngine.listenToRemoteStore(query);
        break;
      default:
        break;
    }

    return queryInfo.targetId;
  }

  /** Removes a previously added listener. It's a no-op if the listener is not found. */
  public void removeQueryListener(QueryListener listener) {
    Query query = listener.getQuery();
    QueryListenersInfo queryInfo = queries.get(query);
    ListenerRemovalAction listenerAction = ListenerRemovalAction.NO_ACTION_REQUIRED;
    if (queryInfo == null) return;

    queryInfo.listeners.remove(listener);
    if (queryInfo.listeners.isEmpty()) {
      listenerAction =
          listener.listensToRemoteStore()
              ? ListenerRemovalAction.TERMINATE_LOCAL_LISTEN_AND_REQUIRE_WATCH_DISCONNECTION
              : ListenerRemovalAction.TERMINATE_LOCAL_LISTEN_ONLY;

    } else if (!queryInfo.hasRemoteListeners() && listener.listensToRemoteStore()) {
      // The removed listener is the last one that sourced from watch.
      listenerAction = ListenerRemovalAction.REQUIRE_WATCH_DISCONNECTION_ONLY;
    }

    switch (listenerAction) {
      case TERMINATE_LOCAL_LISTEN_AND_REQUIRE_WATCH_DISCONNECTION:
        queries.remove(query);
        syncEngine.stopListening(
            query,
            /** shouldUnlistenToRemote= */
            true);
        break;
      case TERMINATE_LOCAL_LISTEN_ONLY:
        queries.remove(query);
        syncEngine.stopListening(
            query,
            /** shouldUnlistenToRemote= */
            false);
        break;
      case REQUIRE_WATCH_DISCONNECTION_ONLY:
        syncEngine.stopListeningToRemoteStore(query);
        break;
      default:
        break;
    }
  }

  public void addSnapshotsInSyncListener(EventListener<Void> listener) {
    snapshotsInSyncListeners.add(listener);
    listener.onEvent(null, null);
  }

  public void removeSnapshotsInSyncListener(EventListener<Void> listener) {
    snapshotsInSyncListeners.remove(listener);
  }

  /** Call all global snapshot listeners that have been set. */
  private void raiseSnapshotsInSyncEvent() {
    for (EventListener<Void> listener : snapshotsInSyncListeners) {
      listener.onEvent(null, null);
    }
  }

  @Override
  public void onViewSnapshots(List<ViewSnapshot> snapshotList) {
    boolean raisedEvent = false;
    for (ViewSnapshot viewSnapshot : snapshotList) {
      Query query = viewSnapshot.getQuery();
      QueryListenersInfo info = queries.get(query);
      if (info != null) {
        for (QueryListener listener : info.listeners) {
          if (listener.onViewSnapshot(viewSnapshot)) {
            raisedEvent = true;
          }
        }
        info.viewSnapshot = viewSnapshot;
      }
    }
    if (raisedEvent) {
      raiseSnapshotsInSyncEvent();
    }
  }

  @Override
  public void onError(Query query, Status error) {
    QueryListenersInfo info = queries.get(query);
    if (info != null) {
      for (QueryListener listener : info.listeners) {
        listener.onError(Util.exceptionFromStatus(error));
      }
    }
    queries.remove(query);
  }

  @Override
  public void handleOnlineStateChange(OnlineState onlineState) {
    boolean raisedEvent = false;
    this.onlineState = onlineState;
    for (QueryListenersInfo info : queries.values()) {
      for (QueryListener listener : info.listeners) {
        if (listener.onOnlineStateChanged(onlineState)) {
          raisedEvent = true;
        }
      }
    }
    if (raisedEvent) {
      raiseSnapshotsInSyncEvent();
    }
  }
}
