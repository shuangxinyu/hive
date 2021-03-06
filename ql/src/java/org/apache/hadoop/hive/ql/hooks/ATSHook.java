/**
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
package org.apache.hadoop.hive.ql.hooks;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.QueryPlan;
import org.apache.hadoop.hive.ql.exec.ExplainTask;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvent;
import org.apache.hadoop.yarn.client.api.TimelineClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import org.json.JSONObject;

import static org.apache.hadoop.hive.ql.hooks.HookContext.HookType.*;

/**
 * ATSHook sends query + plan info to Yarn App Timeline Server. To enable (hadoop 2.4 and up) set
 * hive.exec.pre.hooks/hive.exec.post.hooks/hive.exec.failure.hooks to include this class.
 */
public class ATSHook implements ExecuteWithHookContext {

  private static final Log LOG = LogFactory.getLog(ATSHook.class.getName());
  private static final Object LOCK = new Object();
  private static ExecutorService executor;
  private static TimelineClient timelineClient;
  private enum EntityTypes { HIVE_QUERY_ID };
  private enum EventTypes { QUERY_SUBMITTED, QUERY_COMPLETED };
  private enum OtherInfoTypes { QUERY, STATUS, TEZ, MAPRED };
  private enum PrimaryFilterTypes { user };
  private static final int WAIT_TIME = 3;

  public ATSHook() {
    synchronized(LOCK) {
      if (executor == null) {

        executor = Executors.newSingleThreadExecutor(
           new ThreadFactoryBuilder().setDaemon(true).setNameFormat("ATS Logger %d").build());

        YarnConfiguration yarnConf = new YarnConfiguration();
        timelineClient = TimelineClient.createTimelineClient();
        timelineClient.init(yarnConf);
        timelineClient.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
          @Override
          public void run() {
            try {
              executor.shutdown();
              executor.awaitTermination(WAIT_TIME, TimeUnit.SECONDS);
              executor = null;
            } catch(InterruptedException ie) { /* ignore */ }
            timelineClient.stop();
          }
        });
      }
    }

    LOG.info("Created ATS Hook");
  }

  @Override
  public void run(final HookContext hookContext) throws Exception {
    final long currentTime = System.currentTimeMillis();
    executor.submit(new Runnable() {
        @Override
        public void run() {
          try {
            QueryPlan plan = hookContext.getQueryPlan();
            if (plan == null) {
              return;
            }
            String queryId = plan.getQueryId();
            long queryStartTime = plan.getQueryStartTime();
            String user = hookContext.getUgi().getUserName();
            int numMrJobs = Utilities.getMRTasks(plan.getRootTasks()).size();
            int numTezJobs = Utilities.getTezTasks(plan.getRootTasks()).size();

            if (numMrJobs + numTezJobs <= 0) {
              return; // ignore client only queries
            }

            switch(hookContext.getHookType()) {
            case PRE_EXEC_HOOK:
              ExplainTask explain = new ExplainTask();
              explain.initialize(hookContext.getConf(), plan, null);
              String query = plan.getQueryStr();
              JSONObject explainPlan = explain.getJSONPlan(null, null, plan.getRootTasks(),
                   plan.getFetchTask(), true, false, false);
              fireAndForget(hookContext.getConf(), createPreHookEvent(queryId, query,
                   explainPlan, queryStartTime, user, numMrJobs, numTezJobs));
              break;
            case POST_EXEC_HOOK:
              fireAndForget(hookContext.getConf(), createPostHookEvent(queryId, currentTime, user, true));
              break;
            case ON_FAILURE_HOOK:
              fireAndForget(hookContext.getConf(), createPostHookEvent(queryId, currentTime, user, false));
              break;
            default:
              //ignore
              break;
            }
          } catch (Exception e) {
            LOG.info("Failed to submit plan to ATS: " + StringUtils.stringifyException(e));
          }
        }
      });
  }

  TimelineEntity createPreHookEvent(String queryId, String query, JSONObject explainPlan,
      long startTime, String user, int numMrJobs, int numTezJobs) throws Exception {

    JSONObject queryObj = new JSONObject();
    queryObj.put("queryText", query);
    queryObj.put("queryPlan", explainPlan);

    LOG.info("Received pre-hook notification for :" + queryId);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Otherinfo: " + queryObj.toString());
    }

    TimelineEntity atsEntity = new TimelineEntity();
    atsEntity.setEntityId(queryId);
    atsEntity.setEntityType(EntityTypes.HIVE_QUERY_ID.name());
    atsEntity.addPrimaryFilter(PrimaryFilterTypes.user.name(), user);

    TimelineEvent startEvt = new TimelineEvent();
    startEvt.setEventType(EventTypes.QUERY_SUBMITTED.name());
    startEvt.setTimestamp(startTime);
    atsEntity.addEvent(startEvt);

    atsEntity.addOtherInfo(OtherInfoTypes.QUERY.name(), queryObj.toString());
    atsEntity.addOtherInfo(OtherInfoTypes.TEZ.name(), numTezJobs > 0);
    atsEntity.addOtherInfo(OtherInfoTypes.MAPRED.name(), numMrJobs > 0);
    return atsEntity;
  }

  TimelineEntity createPostHookEvent(String queryId, long stopTime, String user, boolean success) {
    LOG.info("Received post-hook notification for :" + queryId);

    TimelineEntity atsEntity = new TimelineEntity();
    atsEntity.setEntityId(queryId);
    atsEntity.setEntityType(EntityTypes.HIVE_QUERY_ID.name());
    atsEntity.addPrimaryFilter(PrimaryFilterTypes.user.name(), user);

    TimelineEvent stopEvt = new TimelineEvent();
    stopEvt.setEventType(EventTypes.QUERY_COMPLETED.name());
    stopEvt.setTimestamp(stopTime);
    atsEntity.addEvent(stopEvt);

    atsEntity.addOtherInfo(OtherInfoTypes.STATUS.name(), success);

    return atsEntity;
  }

  synchronized void fireAndForget(Configuration conf, TimelineEntity entity) throws Exception {
    timelineClient.putEntities(entity);
  }
}
