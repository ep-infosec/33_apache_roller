/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.business.jpa;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.pings.AutoPingManager;
import org.apache.roller.weblogger.business.pings.PingQueueManager;
import org.apache.roller.weblogger.config.PingConfig;
import org.apache.roller.weblogger.pojos.AutoPing;
import org.apache.roller.weblogger.pojos.PingTarget;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.Weblog;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.apache.roller.weblogger.business.Weblogger;

/*
 * JPAAutoPingManagerImpl.java
 *
 * Created on May 29, 2006, 11:29 AM
 *
 */
@com.google.inject.Singleton
public class JPAAutoPingManagerImpl implements AutoPingManager {

    private final Weblogger roller;
    private final JPAPersistenceStrategy strategy;
    /**
     * The logger instance for this class.
     */
    private static final Log logger = LogFactory.getFactory().getInstance(JPAAutoPingManagerImpl.class);

    /**
     * Creates a new instance of JPAAutoPingManagerImpl
     */
    @com.google.inject.Inject
    protected JPAAutoPingManagerImpl(Weblogger roller, JPAPersistenceStrategy strategy) {
        this.roller = roller;
        this.strategy = strategy;
    }

    @Override
    public AutoPing getAutoPing(String id) throws WebloggerException {
        return (AutoPing) strategy.load(AutoPing.class, id);
    }

    @Override
    public void saveAutoPing(AutoPing autoPing) throws WebloggerException {
        strategy.store(autoPing);
    }

    @Override
    public void removeAutoPing(AutoPing autoPing) throws WebloggerException {
        strategy.remove(autoPing);
    }

    @Override
    public void removeAutoPing(PingTarget pingTarget, Weblog website) throws WebloggerException {
        Query q = strategy.getNamedUpdate("AutoPing.removeByPingTarget&Website");
        q.setParameter(1, pingTarget);
        q.setParameter(2, website);
        q.executeUpdate();
    }

    @Override
    public void removeAutoPings(Collection<AutoPing> autopings) throws WebloggerException {
        strategy.removeAll(autopings);
    }

    @Override
    public void removeAllAutoPings() throws WebloggerException {
        TypedQuery<AutoPing> q = strategy.getNamedQueryCommitFirst("AutoPing.getAll", AutoPing.class);
        removeAutoPings(q.getResultList());
    }

    @Override
    public void queueApplicableAutoPings(WeblogEntry changedWeblogEntry) throws WebloggerException {
        if (PingConfig.getSuspendPingProcessing()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Ping processing is suspended." + " No auto pings will be queued.");
            }
            return;
        }

        PingQueueManager pingQueueMgr = roller.getPingQueueManager();
        List<AutoPing> applicableAutopings = getApplicableAutoPings(changedWeblogEntry);
        for (AutoPing autoPing : applicableAutopings) {
            pingQueueMgr.addQueueEntry(autoPing);
        }
    }

    @Override
    public List<AutoPing> getAutoPingsByWebsite(Weblog website) throws WebloggerException {
        TypedQuery<AutoPing> q = strategy.getNamedQuery("AutoPing.getByWebsite", AutoPing.class);
        q.setParameter(1, website);
        return q.getResultList();
    }

    @Override
    public List<AutoPing> getAutoPingsByTarget(PingTarget pingTarget) throws WebloggerException {
        TypedQuery<AutoPing> q = strategy.getNamedQuery("AutoPing.getByPingTarget", AutoPing.class);
        q.setParameter(1, pingTarget);
        return q.getResultList();
    }

    @Override
    public List<AutoPing> getApplicableAutoPings(WeblogEntry changedWeblogEntry) throws WebloggerException {
        return getAutoPingsByWebsite(changedWeblogEntry.getWebsite());
    }

    @Override
    public void release() {
    }
}