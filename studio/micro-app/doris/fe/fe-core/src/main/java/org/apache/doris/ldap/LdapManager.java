// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.ldap;

import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.Env;
import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.LdapConfig;
import org.apache.doris.mysql.privilege.Auth;
import org.apache.doris.mysql.privilege.Role;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Encapsulates LDAP service interfaces and caches user LDAP information.
 */
public class LdapManager {
    private static final Logger LOG = LogManager.getLogger(LdapManager.class);

    public static final String LDAP_GROUPS_PRIVS_NAME = "ldapGroupsPrivs";

    private final LdapClient ldapClient = new LdapClient();

    private final Map<String, LdapUserInfo> ldapUserInfoCache = Maps.newHashMap();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private void readLock() {
        lock.readLock().lock();
    }

    private void readUnlock() {
        lock.readLock().unlock();
    }

    private void writeLock() {
        lock.writeLock().lock();
    }

    private void writeUnlock() {
        lock.writeLock().unlock();
    }

    private volatile long lastTimestamp = System.currentTimeMillis();

    // If the user exists in LDAP, the LDAP information of the user is returned; otherwise, null is returned.
    public LdapUserInfo getUserInfo(String fullName) {
        if (!checkParam(fullName)) {
            return null;
        }
        LdapUserInfo ldapUserInfo = getUserInfoFromCache(fullName);
        if (ldapUserInfo != null && !ldapUserInfo.checkTimeout()) {
            return ldapUserInfo;
        }
        try {
            return getUserInfoAndUpdateCache(fullName);
        } catch (DdlException e) {
            LOG.warn("getUserInfo for {} failed", fullName, e);
            return null;
        }
    }

    public boolean doesUserExist(String fullName) {
        if (!checkParam(fullName)) {
            return false;
        }
        LdapUserInfo info = getUserInfo(fullName);
        return !Objects.isNull(info) && info.isExists();
    }

    public boolean checkUserPasswd(String fullName, String passwd) {
        String userName = ClusterNamespace.getNameFromFullName(fullName);
        if (!LdapConfig.ldap_authentication_enabled || Strings.isNullOrEmpty(userName) || Objects.isNull(passwd)) {
            return false;
        }
        LdapUserInfo ldapUserInfo = getUserInfo(fullName);
        if (Objects.isNull(ldapUserInfo) || !ldapUserInfo.isExists()) {
            return false;
        }

        if (ldapUserInfo.isSetPasswd() && ldapUserInfo.getPasswd().equals(passwd)) {
            return true;
        }
        boolean isRightPasswd = ldapClient.checkPassword(userName, passwd);
        if (!isRightPasswd) {
            return false;
        }
        updatePasswd(ldapUserInfo, passwd);
        return true;
    }

    public boolean checkUserPasswd(String fullName, String passwd, String remoteIp, List<UserIdentity> currentUser) {
        if (checkUserPasswd(fullName, passwd)) {
            currentUser.addAll(Env.getCurrentEnv().getAuth().getUserIdentityForLdap(fullName, remoteIp));
            return true;
        }
        return false;
    }

    public Role getUserRole(String fullName) {
        LdapUserInfo info = getUserInfo(fullName);
        return !Objects.isNull(info) && info.isExists() ? info.getPaloRole() : new Role(LDAP_GROUPS_PRIVS_NAME);
    }

    private boolean checkParam(String fullName) {
        return LdapConfig.ldap_authentication_enabled && !Strings.isNullOrEmpty(fullName) && !fullName.equalsIgnoreCase(
                Auth.ROOT_USER) && !fullName.equalsIgnoreCase(Auth.ADMIN_USER);
    }

    private LdapUserInfo getUserInfoAndUpdateCache(String fulName) throws DdlException {
        String cluster = ClusterNamespace.getClusterNameFromFullName(fulName);
        String userName = ClusterNamespace.getNameFromFullName(fulName);
        if (Strings.isNullOrEmpty(userName)) {
            return null;
        } else if (!ldapClient.doesUserExist(userName)) {
            return makeUserNotExists(fulName);
        }
        checkTimeoutCleanCache();

        LdapUserInfo ldapUserInfo = new LdapUserInfo(fulName, false, "", getLdapGroupsPrivs(userName, cluster));
        writeLock();
        try {
            ldapUserInfoCache.put(ldapUserInfo.getUserName(), ldapUserInfo);
        } finally {
            writeUnlock();
        }
        return ldapUserInfo;
    }

    private void updatePasswd(LdapUserInfo ldapUserInfo, String passwd) {
        LdapUserInfo newLdapUserInfo = ldapUserInfo.cloneWithPasswd(passwd);
        writeLock();
        try {
            ldapUserInfoCache.put(newLdapUserInfo.getUserName(), newLdapUserInfo);
        } finally {
            writeUnlock();
        }
    }

    private LdapUserInfo makeUserNotExists(String fullName) {
        writeLock();
        try {
            return ldapUserInfoCache.put(fullName, new LdapUserInfo(fullName));
        } finally {
            writeUnlock();
        }
    }

    private void checkTimeoutCleanCache() {
        long tempTimestamp = System.currentTimeMillis() - LdapConfig.ldap_cache_timeout_day * 24 * 60 * 60 * 1000;
        if (lastTimestamp < tempTimestamp) {
            writeLock();
            try {
                if (lastTimestamp < tempTimestamp) {
                    ldapUserInfoCache.clear();
                    lastTimestamp = System.currentTimeMillis();
                }
            } finally {
                writeUnlock();
            }
        }
    }

    private LdapUserInfo getUserInfoFromCache(String fullName) {
        readLock();
        try {
            return ldapUserInfoCache.get(fullName);
        } finally {
            readUnlock();
        }
    }

    /**
     * Step1: get ldap groups from ldap server;
     * Step2: get roles by ldap groups;
     * Step3: merge the roles;
     */
    private Role getLdapGroupsPrivs(String userName, String clusterName) throws DdlException {
        //get user ldap group. the ldap group name should be the same as the doris role name
        List<String> ldapGroups = ldapClient.getGroups(userName);
        List<String> rolesNames = Lists.newArrayList();
        for (String group : ldapGroups) {
            String qualifiedRole = ClusterNamespace.getFullName(clusterName, group);
            if (Env.getCurrentEnv().getAuth().doesRoleExist(qualifiedRole)) {
                rolesNames.add(qualifiedRole);
            }
        }
        LOG.debug("get user:{} ldap groups:{} and doris roles:{}", userName, ldapGroups, rolesNames);

        Role ldapGroupsPrivs = new Role(LDAP_GROUPS_PRIVS_NAME);
        LdapPrivsChecker.grantDefaultPrivToTempUser(ldapGroupsPrivs, clusterName);
        if (!rolesNames.isEmpty()) {
            Env.getCurrentEnv().getAuth().mergeRolesNoCheckName(rolesNames, ldapGroupsPrivs);
        }
        return ldapGroupsPrivs;
    }

    public void refresh(boolean isAll, String fullName) {
        writeLock();
        try {
            if (isAll) {
                ldapUserInfoCache.clear();
                lastTimestamp = System.currentTimeMillis();
                LOG.info("refreshed all ldap info.");
            } else {
                ldapUserInfoCache.remove(fullName);
                LOG.info("refreshed ldap info for " + fullName);
            }
        } finally {
            writeUnlock();
        }
    }
}
