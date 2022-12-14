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

package org.apache.roller.weblogger.business.startup;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.DatabaseProvider;


/**
 * Handles the install/upgrade of the Roller Weblogger database when the user
 * has configured their installation type to 'auto'.
 */
public class DatabaseInstaller {

    private static Log log = LogFactory.getLog(DatabaseInstaller.class);

    private final DatabaseProvider db;
    private final DatabaseScriptProvider scripts;
    private final String version;
    private List<String> messages = new ArrayList<>();

    // the name of the property which holds the dbversion value
    private static final String DBVERSION_PROP = "roller.database.version";


    public DatabaseInstaller(DatabaseProvider dbProvider, DatabaseScriptProvider scriptProvider) {
        db = dbProvider;
        scripts = scriptProvider;

        Properties props = new Properties();
        try {
            props.load(getClass().getResourceAsStream("/roller-version.properties"));
        } catch (IOException e) {
            log.error("roller-version.properties not found", e);
        }

        version = props.getProperty("ro.version", "UNKNOWN");
    }


    /**
     * Determine if database schema needs to be upgraded.
     */
    public boolean isCreationRequired() {
        Connection con = null;
        try {
            con = db.getConnection();

            // just check for a couple key Roller tables
            // roller_user table called rolleruser before Roller 5.1
            if (tableExists(con, "userrole") && (tableExists(con, "roller_user") || tableExists(con, "rolleruser"))) {
                return false;
            }

        } catch (Exception e) {
            throw new RuntimeException("Error checking for tables", e);
        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (Exception ignored) {}
        }

        return true;
    }


    /**
     * Determine if database schema needs to be upgraded.
     */
    public boolean isUpgradeRequired() {
        int desiredVersion = parseVersionString(version);
        int databaseVersion;
        try {
            databaseVersion = getDatabaseVersion();
        } catch (StartupException ex) {
            throw new RuntimeException(ex);
        }

        // if dbversion is unset then assume a new install, otherwise compare
        if (databaseVersion < 0) {
            // if this is a fresh db then we need to set the database version
            Connection con = null;
            try {
                con = db.getConnection();
                setDatabaseVersion(con, version);
            } catch (Exception ioe) {
                errorMessage("ERROR setting database version");
            } finally {
                try {
                    if (con != null) {
                        con.close();
                    }
                } catch (Exception ignored) {
                }
            }

            return false;
        } else {
            return databaseVersion < desiredVersion;
        }
    }


    public List<String> getMessages() {
        return messages;
    }


    private void errorMessage(String msg) {
        messages.add(msg);
        log.error(msg);
    }


    private void errorMessage(String msg, Throwable t) {
        messages.add(msg);
        log.error(msg, t);
    }


    private void successMessage(String msg) {
        messages.add(msg);
        log.trace(msg);
    }


    /**
     * Create datatabase tables.
     */
    public void createDatabase() throws StartupException {

        log.info("Creating Roller Weblogger database tables.");

        Connection con = null;
        SQLScriptRunner create = null;
        try {
            con = db.getConnection();
            String handle = getDatabaseHandle(con);
            create = new SQLScriptRunner(scripts.getDatabaseScript(handle + "/createdb.sql"));
            create.runScript(con, true);
            messages.addAll(create.getMessages());

            setDatabaseVersion(con, version);

        } catch (SQLException sqle) {
            log.error("ERROR running SQL in database creation script", sqle);
            if (create != null) {
                messages.addAll(create.getMessages());
            }
            errorMessage("ERROR running SQL in database creation script");
            throw new StartupException("Error running sql script", sqle);

        } catch (Exception ioe) {
            log.error("ERROR running database creation script", ioe);
            if (create != null) {
                messages.addAll(create.getMessages());
            }
            errorMessage("ERROR reading/parsing database creation script");
            throw new StartupException("Error running SQL script", ioe);

        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (Exception ignored) {}
        }
    }


    /**
     * Upgrade database if dbVersion is older than desiredVersion.
     */
    public void upgradeDatabase(boolean runScripts) throws StartupException {

        int myVersion = parseVersionString(version);
        int dbversion = getDatabaseVersion();

        log.info("Database version = "+dbversion);
        log.info("Desired version = "+myVersion);

        Connection con = null;
        try {
            con = db.getConnection();
            if(dbversion < 0) {
                String msg = "Cannot upgrade database tables, Roller database version cannot be determined";
                errorMessage(msg);
                throw new StartupException(msg);
            } else if (dbversion < 310) {
                String msg = "Roller " + myVersion + " cannot upgrade from versions older than 3.10; " +
                        "try first upgrading to an earlier version of Roller.";
                errorMessage(msg);
                throw new StartupException(msg);
            } else if(dbversion >= myVersion) {
                log.info("Database is current, no upgrade needed");
                return;
            }

            log.info("Database is old, beginning upgrade to version "+myVersion);

            // iterate through each upgrade as needed
            // to add to the upgrade sequence simply add a new "if" statement
            // for whatever version needed and then define a new method upgradeXXX()

            if(dbversion < 400) {
                upgradeTo400(con, runScripts);
                dbversion = 400;
            }
            if(dbversion < 500) {
                upgradeTo500(con, runScripts);
                dbversion = 500;
            }
            if(dbversion < 510) {
                upgradeTo510(con, runScripts);
                dbversion = 510;
            }
            if(dbversion < 520) {
                upgradeTo520(con, runScripts);
                dbversion = 520;
            }
            if(dbversion < 610) {
                upgradeTo610(con, runScripts);
                dbversion = 610;
            }

            // make sure the database version is the exact version
            // we are upgrading too.
            updateDatabaseVersion(con, myVersion);

        } catch (SQLException e) {
            throw new StartupException("ERROR obtaining connection");
        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Upgrade database to Roller 4.0.0
     */
    private void upgradeTo400(Connection con, boolean runScripts) throws StartupException {

        successMessage("Doing upgrade to 400 ...");

        // first we need to run upgrade scripts
        SQLScriptRunner runner = null;
        try {
            if (runScripts) {
                String handle = getDatabaseHandle(con);
                String scriptPath = handle + "/310-to-400-migration.sql";
                successMessage("Running database upgrade script: "+scriptPath);
                runner = new SQLScriptRunner(scripts.getDatabaseScript(scriptPath));
                runner.runScript(con, true);
                messages.addAll(runner.getMessages());
            }
        } catch(Exception ex) {
            log.error("ERROR running 400 database upgrade script", ex);
            if (runner != null) {
                messages.addAll(runner.getMessages());
            }

            errorMessage("Problem upgrading database to version 400", ex);
            throw new StartupException("Problem upgrading database to version 400", ex);
        }


        // now upgrade hierarchical objects data model
        try {
            successMessage("Populating parentid columns for weblogcategory and folder tables");

            // Populate parentid in weblogcategory and folder tables.
            //
            // We'd like to do something like the below, but few databases
            // support multiple table udpates, which are part of SQL-99
            //
            // update weblogcategory, weblogcategoryassoc
            //   set weblogcategory.parentid = weblogcategoryassoc.ancestorid
            //   where
            //      weblogcategory.id = weblogcategoryassoc.categoryid
            //      and weblogcategoryassoc.relation = 'PARENT';
            //
            // update folder,folderassoc
            //   set folder.parentid = folderassoc.ancestorid
            //   where
            //      folder.id = folderassoc.folderid
            //      and folderassoc.relation = 'PARENT';

            PreparedStatement selectParents = con.prepareStatement(
                "select categoryid, ancestorid from weblogcategoryassoc where relation='PARENT'");
            PreparedStatement updateParent = con.prepareStatement(
                "update weblogcategory set parentid=? where id=?");
            ResultSet parentSet = selectParents.executeQuery();
            while (parentSet.next()) {
                String categoryid = parentSet.getString(1);
                String parentid = parentSet.getString(2);
                updateParent.clearParameters();
                updateParent.setString( 1, parentid);
                updateParent.setString( 2, categoryid);
                updateParent.executeUpdate();
            }

            selectParents = con.prepareStatement(
                "select folderid, ancestorid from folderassoc where relation='PARENT'");
            updateParent = con.prepareStatement(
                "update folder set parentid=? where id=?");
            parentSet = selectParents.executeQuery();
            while (parentSet.next()) {
                String folderid = parentSet.getString(1);
                String parentid = parentSet.getString(2);
                updateParent.clearParameters();
                updateParent.setString( 1, parentid);
                updateParent.setString( 2, folderid);
                updateParent.executeUpdate();
            }

            if (!con.getAutoCommit()) {
                con.commit();
            }

            successMessage("Done populating parentid columns.");

        } catch (Exception e) {
            errorMessage("Problem upgrading database to version 320", e);
            throw new StartupException("Problem upgrading database to version 320", e);
        }


        try {
            successMessage("Populating path columns for weblogcategory and folder tables.");

            // Populate path in weblogcategory and folder tables.
            //
            // It would be nice if there was a simple sql solution for doing
            // this, but sadly the only real way to do it is through brute
            // force walking the hierarchical trees.  Luckily, it seems that
            // most people don't create multi-level hierarchies, so hopefully
            // this won't be too bad

            // set path to '/' for nodes with no parents (aka root nodes)
            PreparedStatement setRootPaths = con.prepareStatement(
                "update weblogcategory set path = '/' where parentid is NULL");
            setRootPaths.clearParameters();
            setRootPaths.executeUpdate();

            // select all nodes whose parent has no parent (aka 1st level nodes)
            PreparedStatement selectL1Children = con.prepareStatement(
                "select f.id, f.name from weblogcategory f, weblogcategory p "+
                    "where f.parentid = p.id and p.parentid is NULL");
            // update L1 nodes with their path (/<name>)
            PreparedStatement updateL1Children = con.prepareStatement(
                "update weblogcategory set path=? where id=?");
            ResultSet L1Set = selectL1Children.executeQuery();
            while (L1Set.next()) {
                String id = L1Set.getString(1);
                String name = L1Set.getString(2);
                updateL1Children.clearParameters();
                updateL1Children.setString( 1, "/"+name);
                updateL1Children.setString( 2, id);
                updateL1Children.executeUpdate();
            }

            // now for the complicated part =(
            // we need to keep iterating over L2, L3, etc nodes and setting
            // their path until all nodes have been updated.

            // select all nodes whose parent path has been set, excluding L1 nodes
            PreparedStatement selectLxChildren = con.prepareStatement(
                "select f.id, f.name, p.path from weblogcategory f, weblogcategory p "+
                    "where f.parentid = p.id and p.path <> '/' "+
                    "and p.path is not NULL and f.path is NULL");
            // update Lx nodes with their path (<parentPath>/<name>)
            PreparedStatement updateLxChildren = con.prepareStatement(
                "update weblogcategory set path=? where id=?");

            // this loop allows us to run this part of the upgrade process as
            // long as is necessary based on the depth of the hierarchy, and
            // we use the do/while construct to ensure it's run at least once
            int catNumCounted = 0;
            do {
                log.debug("Doing pass over Lx children for categories");

                // reset count for each iteration of outer loop
                catNumCounted = 0;

                ResultSet LxSet = selectLxChildren.executeQuery();
                while (LxSet.next()) {
                    String id = LxSet.getString(1);
                    String name = LxSet.getString(2);
                    String parentPath = LxSet.getString(3);
                    updateLxChildren.clearParameters();
                    updateLxChildren.setString( 1, parentPath+"/"+name);
                    updateLxChildren.setString( 2, id);
                    updateLxChildren.executeUpdate();

                    // count the updated rows
                    catNumCounted++;
                }

                log.debug("Updated "+catNumCounted+" Lx category paths");
            } while(catNumCounted > 0);



            // set path to '/' for nodes with no parents (aka root nodes)
            setRootPaths = con.prepareStatement(
                "update folder set path = '/' where parentid is NULL");
            setRootPaths.clearParameters();
            setRootPaths.executeUpdate();

            // select all nodes whose parent has no parent (aka 1st level nodes)
            selectL1Children = con.prepareStatement(
                "select f.id, f.name from folder f, folder p "+
                    "where f.parentid = p.id and p.parentid is NULL");
            // update L1 nodes with their path (/<name>)
            updateL1Children = con.prepareStatement(
                "update folder set path=? where id=?");
            L1Set = selectL1Children.executeQuery();
            while (L1Set.next()) {
                String id = L1Set.getString(1);
                String name = L1Set.getString(2);
                updateL1Children.clearParameters();
                updateL1Children.setString( 1, "/"+name);
                updateL1Children.setString( 2, id);
                updateL1Children.executeUpdate();
            }

            // now for the complicated part =(
            // we need to keep iterating over L2, L3, etc nodes and setting
            // their path until all nodes have been updated.

            // select all nodes whose parent path has been set, excluding L1 nodes
            selectLxChildren = con.prepareStatement(
                "select f.id, f.name, p.path from folder f, folder p "+
                    "where f.parentid = p.id and p.path <> '/' "+
                    "and p.path is not NULL and f.path is NULL");
            // update Lx nodes with their path (/<name>)
            updateLxChildren = con.prepareStatement(
                "update folder set path=? where id=?");

            // this loop allows us to run this part of the upgrade process as
            // long as is necessary based on the depth of the hierarchy, and
            // we use the do/while construct to ensure it's run at least once
            int folderNumUpdated = 0;
            do {
                log.debug("Doing pass over Lx children for folders");

                // reset count for each iteration of outer loop
                folderNumUpdated = 0;

                ResultSet LxSet = selectLxChildren.executeQuery();
                while (LxSet.next()) {
                    String id = LxSet.getString(1);
                    String name = LxSet.getString(2);
                    String parentPath = LxSet.getString(3);
                    updateLxChildren.clearParameters();
                    updateLxChildren.setString( 1, parentPath+"/"+name);
                    updateLxChildren.setString( 2, id);
                    updateLxChildren.executeUpdate();

                    // count the updated rows
                    folderNumUpdated++;
                }

                log.debug("Updated "+folderNumUpdated+" Lx folder paths");
            } while(folderNumUpdated > 0);

            if (!con.getAutoCommit()) {
                con.commit();
            }

            successMessage("Done populating path columns.");

        } catch (SQLException e) {
            log.error("Problem upgrading database to version 320", e);
            throw new StartupException("Problem upgrading database to version 320", e);
        }


        // 4.0 changes the planet data model a bit, so we need to clean that up
        try {
            successMessage("Merging planet groups 'all' and 'external'");

            // Move all subscriptions in the planet group 'external' to group 'all'

            String allGroupId = null;
            PreparedStatement selectAllGroupId = con.prepareStatement(
                "select id from rag_group where handle = 'all'");
            ResultSet rs = selectAllGroupId.executeQuery();
            if (rs.next()) {
                allGroupId = rs.getString(1);
            }

            String externalGroupId = null;
            PreparedStatement selectExternalGroupId = con.prepareStatement(
                "select id from rag_group where handle = 'external'");
            rs = selectExternalGroupId.executeQuery();
            if (rs.next()) {
                externalGroupId = rs.getString(1);
            }

            // we only need to merge if both of those groups already existed
            if(allGroupId != null && externalGroupId != null) {
                PreparedStatement updateGroupSubs = con.prepareStatement(
                        "update rag_group_subscription set group_id = ? where group_id = ?");
                updateGroupSubs.clearParameters();
                updateGroupSubs.setString( 1, allGroupId);
                updateGroupSubs.setString( 2, externalGroupId);
                updateGroupSubs.executeUpdate();

                // we no longer need the group 'external'
                PreparedStatement deleteExternalGroup = con.prepareStatement(
                        "delete from rag_group where handle = 'external'");
                deleteExternalGroup.executeUpdate();

            // if we only have group 'external' then just rename it to 'all'
            } else if(allGroupId == null && externalGroupId != null) {

                // rename 'external' to 'all'
                PreparedStatement renameExternalGroup = con.prepareStatement(
                        "update rag_group set handle = 'all' where handle = 'external'");
                renameExternalGroup.executeUpdate();
            }

            if (!con.getAutoCommit()) {
                con.commit();
            }

            successMessage("Planet group 'external' merged into group 'all'.");

        } catch (Exception e) {
            errorMessage("Problem upgrading database to version 400", e);
            throw new StartupException("Problem upgrading database to version 400", e);
        }


        // update local planet subscriptions to use new local feed format
        try {
            successMessage("Upgrading local planet subscription feeds to new feed url format");

            // need to start by looking up absolute site url
            PreparedStatement selectAbsUrl =
                    con.prepareStatement("select value from roller_properties where name = 'site.absoluteurl'");
            String absUrl = null;
            ResultSet rs = selectAbsUrl.executeQuery();
            if(rs.next()) {
                absUrl = rs.getString(1);
            }

            if(absUrl != null && absUrl.length() > 0) {
                PreparedStatement selectSubs =
                        con.prepareStatement("select id,feed_url,author from rag_subscription");

            PreparedStatement updateSubUrl =
                    con.prepareStatement("update rag_subscription set last_updated=last_updated, feed_url = ? where id = ?");

            ResultSet rset = selectSubs.executeQuery();
            while (rset.next()) {
                String id = rset.getString(1);
                String feed_url = rset.getString(2);
                String handle = rset.getString(3);

                // only work on local feed urls
                if (feed_url.startsWith(absUrl)) {
                    // update feed_url to 'weblogger:<handle>'
                    updateSubUrl.clearParameters();
                    updateSubUrl.setString( 1, "weblogger:"+handle);
                    updateSubUrl.setString( 2, id);
                    updateSubUrl.executeUpdate();
                }
            }
            }

            if (!con.getAutoCommit()) {
                con.commit();
            }

            successMessage("Comments successfully updated to use new comment plugins.");

        } catch (Exception e) {
            errorMessage("Problem upgrading database to version 400", e);
            throw new StartupException("Problem upgrading database to version 400", e);
        }


        // upgrade comments to use new plugin mechanism
        try {
            successMessage("Upgrading existing comments with content-type & plugins");

            // look in db and see if comment autoformatting is enabled
            boolean autoformatEnabled = false;
            String autoformat = null;
            PreparedStatement selectIsAutoformtEnabled = con.prepareStatement(
                "select value from roller_properties where name = 'users.comments.autoformat'");
            ResultSet rs = selectIsAutoformtEnabled.executeQuery();
            if (rs.next()) {
                autoformat = rs.getString(1);
                if(autoformat != null && "true".equals(autoformat)) {
                    autoformatEnabled = true;
                }
            }

            // look in db and see if comment html escaping is enabled
            boolean htmlEnabled = false;
            String escapehtml = null;
            PreparedStatement selectIsEscapehtmlEnabled = con.prepareStatement(
                "select value from roller_properties where name = 'users.comments.escapehtml'");
            ResultSet rs1 = selectIsEscapehtmlEnabled.executeQuery();
            if (rs1.next()) {
                escapehtml = rs1.getString(1);
                // NOTE: we allow html only when html escaping is OFF
                if(escapehtml != null && !"true".equals(escapehtml)) {
                    htmlEnabled = true;
                }
            }

            // first lets set the new 'users.comments.htmlenabled' property
            PreparedStatement addCommentHtmlProp = con.prepareStatement("insert into roller_properties(name,value) values(?,?)");
            addCommentHtmlProp.clearParameters();
            addCommentHtmlProp.setString(1, "users.comments.htmlenabled");
            if(htmlEnabled) {
                addCommentHtmlProp.setString(2, "true");
            } else {
                addCommentHtmlProp.setString(2, "false");
            }
            addCommentHtmlProp.executeUpdate();

            // determine content-type for existing comments
            String contentType = "text/plain";
            if(htmlEnabled) {
                contentType = "text/html";
            }

            // determine plugins for existing comments
            String plugins = "";
            if(htmlEnabled && autoformatEnabled) {
                plugins = "HTMLSubset,AutoFormat";
            } else if(htmlEnabled) {
                plugins = "HTMLSubset";
            } else if(autoformatEnabled) {
                plugins = "AutoFormat";
            }

            // set new comment plugins configuration property 'users.comments.plugins'
            PreparedStatement addCommentPluginsProp =
                    con.prepareStatement("insert into roller_properties(name,value) values(?,?)");
            addCommentPluginsProp.clearParameters();
            addCommentPluginsProp.setString(1, "users.comments.plugins");
            addCommentPluginsProp.setString(2, plugins);
            addCommentPluginsProp.executeUpdate();

            // set content-type for all existing comments
            PreparedStatement updateCommentsContentType =
                    con.prepareStatement("update roller_comment set posttime=posttime, contenttype = ?");
            updateCommentsContentType.clearParameters();
            updateCommentsContentType.setString(1, contentType);
            updateCommentsContentType.executeUpdate();

            // set plugins for all existing comments
            PreparedStatement updateCommentsPlugins =
                    con.prepareStatement("update roller_comment set posttime=posttime, plugins = ?");
            updateCommentsPlugins.clearParameters();
            updateCommentsPlugins.setString(1, plugins);
            updateCommentsPlugins.executeUpdate();

            if (!con.getAutoCommit()) {
                con.commit();
            }

            successMessage("Comments successfully updated to use new comment plugins.");

        } catch (Exception e) {
            errorMessage("Problem upgrading database to version 400", e);
            throw new StartupException("Problem upgrading database to version 400", e);
        }

        // finally, upgrade db version string to 400
        updateDatabaseVersion(con, 400);
    }


    /**
     * Upgrade database to Roller 5.0
     */
    private void upgradeTo500(Connection con, boolean runScripts) throws StartupException {
        simpleUpgrade(con, 400, 500, runScripts);      
    }

    /**
     * Upgrade database to Roller 5.1
     */
	private void upgradeTo510(Connection con, boolean runScripts) throws StartupException {
        simpleUpgrade(con, 500, 510, runScripts);
	}

    /**
     * Upgrade database to Roller 5.2
     */
    private void upgradeTo520(Connection con, boolean runScripts) throws StartupException {
        simpleUpgrade(con, 510, 520, runScripts);
    }

    /**
     * Upgrade database to Roller 6.1
     */
    private void upgradeTo610(Connection con, boolean runScripts) throws StartupException {
        simpleUpgrade(con, 520, 610, runScripts);
    }
    
    /**
     * Simple upgrade using single SQL migration script.
     */
    private void simpleUpgrade(Connection con, int fromVersion, int toVersion, boolean runScripts) throws StartupException {

        // first we need to run upgrade scripts
        SQLScriptRunner runner = null;
        try {
            if (runScripts) {
                String handle = getDatabaseHandle(con);
                String scriptPath = handle + "/"+fromVersion+"-to-"+toVersion+"-migration.sql";
                
                successMessage("Running database upgrade script: "+scriptPath);
                
                runner = new SQLScriptRunner(scripts.getDatabaseScript(scriptPath));
                runner.runScript(con, true);
                messages.addAll(runner.getMessages());
            }
        } catch(Exception ex) {
            log.error("ERROR running "+fromVersion+"->"+toVersion+" database upgrade script", ex);
            if (runner != null) {
                messages.addAll(runner.getMessages());
            }

            errorMessage("Problem upgrading database to version "+toVersion, ex);
            throw new StartupException("Problem upgrading database to version "+toVersion, ex);
        }
    }

    /**
     * Use database product name to get the database script directory name.
     */
    public String getDatabaseHandle(Connection con) throws SQLException {

        String productName = con.getMetaData().getDatabaseProductName();
        String handle = "mysql";
        if (       productName.toLowerCase().contains("mysql")) {
            handle =  "mysql";
        } else if (productName.toLowerCase().contains("derby")) {
            handle =  "derby";
        } else if (productName.toLowerCase().contains("hsql")) {
            handle =  "hsqldb";
        } else if (productName.toLowerCase().contains("postgres")) {
            handle =  "postgresql";
        } else if (productName.toLowerCase().contains("oracle")) {
            handle =  "oracle";
        } else if (productName.toLowerCase().contains("microsoft")) {
            handle =  "mssql";
        } else if (productName.toLowerCase().contains("db2")) {
            handle =  "db2";
        }

        return handle;
    }


    /**
     * Return true if named table exists in database.
     */
    private boolean tableExists(Connection con, String tableName) throws SQLException {
        ResultSet rs = con.getMetaData().getTables(null, null, "%", null);
        while (rs.next()) {
            if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME").toLowerCase())) {
                return true;
            }
        }
        return false;
    }


    private int getDatabaseVersion() throws StartupException {
        int dbversion = -1;

        // get the current db version
        Connection con = null;
        try {
            con = db.getConnection();
            Statement stmt = con.createStatement();

            // just check in the roller_properties table
            ResultSet rs = stmt.executeQuery(
                    "select value from roller_properties where name = '"+DBVERSION_PROP+"'");

            if(rs.next()) {
                dbversion = Integer.parseInt(rs.getString(1));

            } else {
                // tough to know if this is an upgrade with no db version :/
                // however, if roller_properties is not empty then we at least
                // we have someone upgrading from 1.2.x
                rs = stmt.executeQuery("select count(*) from roller_properties");
                if (rs.next() && rs.getInt(1) > 0) {
                    dbversion = 120;
                }
            }

        } catch(Exception e) {
            // that's strange ... hopefully we didn't need to upgrade
            log.error("Couldn't lookup current database version", e);
        } finally {
            try {
                if (con != null) {
                    con.close();
                }
            } catch (Exception ignored) {}
        }
        return dbversion;
    }


    private int parseVersionString(String vstring) {
        int myversion = 0;

        // NOTE: this assumes a maximum of 3 digits for the version number
        // so if we get to 10.0 then we'll need to upgrade this

        // strip out non-digits
        vstring = vstring.replaceAll("\\Q.\\E", "");
        vstring = vstring.replaceAll("\\D", "");
        if(vstring.length() > 3) {
            vstring = vstring.substring(0, 3);
        }

        // parse to an int
        try {
            int parsed = Integer.parseInt(vstring);
            if(parsed < 100) {
                myversion = parsed * 10;
            } else {
                myversion = parsed;
            }
        } catch(Exception e) {}

        return myversion;
    }


    /**
     * Insert a new database.version property.
     * This should only be called once for new installations
     */
    private void setDatabaseVersion(Connection con, String version)
            throws StartupException {
        setDatabaseVersion(con, parseVersionString(version));
    }

    /**
     * Insert a new database.version property.
     * This should only be called once for new installations
     */
    private void setDatabaseVersion(Connection con, int version)
            throws StartupException {

        try (PreparedStatement stmt = con.prepareStatement("insert into roller_properties values(?,?)")) {
            stmt.setString(1, DBVERSION_PROP);
            stmt.setString(2, String.valueOf(version));
            stmt.executeUpdate();

            log.debug("Set database verstion to "+version);
        } catch(SQLException se) {
            throw new StartupException("Error setting database version.", se);
        }
    }


    /**
     * Update the existing database.version property
     */
    private void updateDatabaseVersion(Connection con, int version)
            throws StartupException {

        try (PreparedStatement stmt = con.prepareStatement("update roller_properties set value = ? where name = ?")) {
            stmt.setString(1, String.valueOf(version));
            stmt.setString(2, DBVERSION_PROP);
            stmt.executeUpdate();

            log.debug("Updated database verstion to "+version);
        } catch(SQLException se) {
            throw new StartupException("Error setting database version.", se);
        }
    }

}
