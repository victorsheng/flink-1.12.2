/*
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

package org.apache.flink.runtime.util;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.Preconditions;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;

/**
 * Utility class for working with Hadoop-related classes. This should only be used if Hadoop is on
 * the classpath.
 */
public class HadoopUtils {

    private static final Logger LOG = LoggerFactory.getLogger(HadoopUtils.class);

    static final Text HDFS_DELEGATION_TOKEN_KIND = new Text("HDFS_DELEGATION_TOKEN");

    //    读取HADOOP_HOME环境变量，如果存在，分别从它的conf和etc/hadoop目录下读取core-site.xml和hdfs-site.xml文件。
    //    从Flink配置文件的fs.hdfs.hdfssite配置项所在目录下寻找。
    //    从Flink配置文件的fs.hdfs.hadoopconf配置项所在目录下寻找。
    //    从HADOOP_CONF_DIR环境变量对应的目录下寻找。
    //    读取Flink配置文件中所有以flink.hadoop.为前缀的key，将这些key截掉这个前缀作为新的key，和原先的value一起作为hadoop
    // conf的配置项，存放入hadoop conf。
    //

    @SuppressWarnings("deprecation")
    public static Configuration getHadoopConfiguration(
            org.apache.flink.configuration.Configuration flinkConfiguration) {

        // Instantiate an HdfsConfiguration to load the hdfs-site.xml and hdfs-default.xml
        // from the classpath

        // 创建个空的conf
        Configuration result = new HdfsConfiguration();

        // 标记是否找到hadoop配置文件
        boolean foundHadoopConfiguration = false;

        // We need to load both core-site.xml and hdfs-site.xml to determine the default fs path and
        // the hdfs configuration.
        // The properties of a newly added resource will override the ones in previous resources, so
        // a configuration
        // file with higher priority should be added later.

        // Approach 1: HADOOP_HOME environment variables

        // 保存两个可能的hadoop conf路径
        String[] possibleHadoopConfPaths = new String[2];

        // 获取HADOOP_HOME环境变量的值
        final String hadoopHome = System.getenv("HADOOP_HOME");
        if (hadoopHome != null) {

            // 如果发现HADOOP_HOME环境变量的值
            // 尝试分别从如下路径获取：
            // $HADOOP_HOME/conf
            // $HADOOP_HOME/etc/hadoop

            // Searching Hadoop configuration files in HADOOP_HOME: /opt/tools/hadoop-3.2.1
            LOG.debug("Searching Hadoop configuration files in HADOOP_HOME: {}", hadoopHome);
            possibleHadoopConfPaths[0] = hadoopHome + "/conf";
            possibleHadoopConfPaths[1] = hadoopHome + "/etc/hadoop"; // hadoop 2.2
        }

        for (String possibleHadoopConfPath : possibleHadoopConfPaths) {
            if (possibleHadoopConfPath != null) {
                // 依次尝试读取possibleHadoopConfPath下的core-site.xml文件和hdfs-site.xml文件到hadoop conf中

                //  Adding /opt/tools/hadoop-3.2.1/etc/hadoop/core-site.xml to hadoop configuration
                //  Adding /opt/tools/hadoop-3.2.1/etc/hadoop/hdfs-site.xml to hadoop configuration
                foundHadoopConfiguration = addHadoopConfIfFound(result, possibleHadoopConfPath);
            }
        }

        // Approach 2: Flink configuration (deprecated)

        // 获取Flink配置项 fs.hdfs.hdfsdefault 对应的配置文件，加入hadoop conf
        final String hdfsDefaultPath =
                flinkConfiguration.getString(ConfigConstants.HDFS_DEFAULT_CONFIG, null);
        if (hdfsDefaultPath != null) {
            result.addResource(new org.apache.hadoop.fs.Path(hdfsDefaultPath));

            LOG.debug(
                    "Using hdfs-default configuration-file path from Flink config: {}",
                    hdfsDefaultPath);
            foundHadoopConfiguration = true;
        }

        // 获取Flink配置项 fs.hdfs.hdfssite 对应的配置文件，加入hadoop conf
        final String hdfsSitePath =
                flinkConfiguration.getString(ConfigConstants.HDFS_SITE_CONFIG, null);
        if (hdfsSitePath != null) {
            result.addResource(new org.apache.hadoop.fs.Path(hdfsSitePath));
            LOG.debug(
                    "Using hdfs-site configuration-file path from Flink config: {}", hdfsSitePath);
            foundHadoopConfiguration = true;
        }

        // 获取Flink配置项 fs.hdfs.hadoopconf 对应的配置文件，加入hadoop conf
        final String hadoopConfigPath =
                flinkConfiguration.getString(ConfigConstants.PATH_HADOOP_CONFIG, null);
        if (hadoopConfigPath != null) {
            LOG.debug("Searching Hadoop configuration files in Flink config: {}", hadoopConfigPath);
            foundHadoopConfiguration =
                    addHadoopConfIfFound(result, hadoopConfigPath) || foundHadoopConfiguration;
        }

        // Approach 3: HADOOP_CONF_DIR environment variable
        // 从系统环境变量HADOOP_CONF_DIR目录中读取hadoop配置文件
        String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");
        if (hadoopConfDir != null) {
            LOG.debug("Searching Hadoop configuration files in HADOOP_CONF_DIR: {}", hadoopConfDir);

            // 读取Flink配置文件中所有以flink.hadoop.为前缀的key
            // 将这些key截掉这个前缀作为新的key，和原先的value一起作为hadoop conf的配置项，存放入hadoop conf

            //  Searching Hadoop configuration files in HADOOP_CONF_DIR:
            // /opt/tools/hadoop-3.2.1/etc/hadoop
            //  Adding /opt/tools/hadoop-3.2.1/etc/hadoop/core-site.xml to hadoop configuration
            //  Adding /opt/tools/hadoop-3.2.1/etc/hadoop/hdfs-site.xml to hadoop configuration

            foundHadoopConfiguration =
                    addHadoopConfIfFound(result, hadoopConfDir) || foundHadoopConfiguration;
        }

        // 如果以上途径均未找到hadoop conf，显示告警信息

        if (!foundHadoopConfiguration) {
            LOG.warn(
                    "Could not find Hadoop configuration via any of the supported methods "
                            + "(Flink configuration, environment variables).");
        }

        return result;
    }

    public static boolean isKerberosSecurityEnabled(UserGroupInformation ugi) {
        return UserGroupInformation.isSecurityEnabled()
                && ugi.getAuthenticationMethod()
                        == UserGroupInformation.AuthenticationMethod.KERBEROS;
    }

    public static boolean areKerberosCredentialsValid(
            UserGroupInformation ugi, boolean useTicketCache) {
        Preconditions.checkState(isKerberosSecurityEnabled(ugi));

        // note: UGI::hasKerberosCredentials inaccurately reports false
        // for logins based on a keytab (fixed in Hadoop 2.6.1, see HADOOP-10786),
        // so we check only in ticket cache scenario.
        if (useTicketCache && !ugi.hasKerberosCredentials()) {
            if (hasHDFSDelegationToken(ugi)) {
                LOG.warn(
                        "Hadoop security is enabled but current login user does not have Kerberos credentials, "
                                + "use delegation token instead. Flink application will terminate after token expires.");
                return true;
            } else {
                LOG.error(
                        "Hadoop security is enabled, but current login user has neither Kerberos credentials "
                                + "nor delegation tokens!");
                return false;
            }
        }

        return true;
    }

    /** Indicates whether the user has an HDFS delegation token. */
    public static boolean hasHDFSDelegationToken(UserGroupInformation ugi) {
        Collection<Token<? extends TokenIdentifier>> usrTok = ugi.getTokens();
        for (Token<? extends TokenIdentifier> token : usrTok) {
            if (token.getKind().equals(HDFS_DELEGATION_TOKEN_KIND)) {
                return true;
            }
        }
        return false;
    }

    /** Checks if the Hadoop dependency is at least the given version. */
    public static boolean isMinHadoopVersion(int major, int minor) throws FlinkRuntimeException {
        final Tuple2<Integer, Integer> hadoopVersion = getMajorMinorBundledHadoopVersion();
        int maj = hadoopVersion.f0;
        int min = hadoopVersion.f1;

        return maj > major || (maj == major && min >= minor);
    }

    /** Checks if the Hadoop dependency is at most the given version. */
    public static boolean isMaxHadoopVersion(int major, int minor) throws FlinkRuntimeException {
        final Tuple2<Integer, Integer> hadoopVersion = getMajorMinorBundledHadoopVersion();
        int maj = hadoopVersion.f0;
        int min = hadoopVersion.f1;

        return maj < major || (maj == major && min < minor);
    }

    private static Tuple2<Integer, Integer> getMajorMinorBundledHadoopVersion() {
        String versionString = VersionInfo.getVersion();
        String[] versionParts = versionString.split("\\.");

        if (versionParts.length < 2) {
            throw new FlinkRuntimeException(
                    "Cannot determine version of Hadoop, unexpected version string: "
                            + versionString);
        }

        int maj = Integer.parseInt(versionParts[0]);
        int min = Integer.parseInt(versionParts[1]);
        return Tuple2.of(maj, min);
    }

    /**
     * Search Hadoop configuration files in the given path, and add them to the configuration if
     * found.
     */
    private static boolean addHadoopConfIfFound(
            Configuration configuration, String possibleHadoopConfPath) {
        boolean foundHadoopConfiguration = false;
        if (new File(possibleHadoopConfPath).exists()) {
            if (new File(possibleHadoopConfPath + "/core-site.xml").exists()) {
                configuration.addResource(
                        new org.apache.hadoop.fs.Path(possibleHadoopConfPath + "/core-site.xml"));

                // Adding /opt/tools/hadoop-3.2.1/etc/hadoop/core-site.xml to hadoop configuration
                LOG.debug(
                        "Adding "
                                + possibleHadoopConfPath
                                + "/core-site.xml to hadoop configuration");
                foundHadoopConfiguration = true;
            }
            //  Adding /opt/tools/hadoop-3.2.1/etc/hadoop/hdfs-site.xml to hadoop configuration
            if (new File(possibleHadoopConfPath + "/hdfs-site.xml").exists()) {
                configuration.addResource(
                        new org.apache.hadoop.fs.Path(possibleHadoopConfPath + "/hdfs-site.xml"));
                LOG.debug(
                        "Adding "
                                + possibleHadoopConfPath
                                + "/hdfs-site.xml to hadoop configuration");
                foundHadoopConfiguration = true;
            }
        }
        return foundHadoopConfiguration;
    }
}
