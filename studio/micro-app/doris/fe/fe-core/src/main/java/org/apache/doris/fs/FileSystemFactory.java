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

package org.apache.doris.fs;

import org.apache.doris.analysis.StorageBackend;
import org.apache.doris.common.FeConstants;
import org.apache.doris.fs.remote.BrokerFileSystem;
import org.apache.doris.fs.remote.RemoteFileSystem;
import org.apache.doris.fs.remote.S3FileSystem;
import org.apache.doris.fs.remote.dfs.DFSFileSystem;
import org.apache.doris.fs.remote.dfs.JFSFileSystem;
import org.apache.doris.fs.remote.dfs.OFSFileSystem;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class FileSystemFactory {

    public static RemoteFileSystem get(String name, StorageBackend.StorageType type, Map<String, String> properties) {
        // TODO: rename StorageBackend.StorageType
        if (type == StorageBackend.StorageType.S3) {
            return new S3FileSystem(properties);
        } else if (type == StorageBackend.StorageType.HDFS || type == StorageBackend.StorageType.GFS) {
            return new DFSFileSystem(properties);
        } else if (type == StorageBackend.StorageType.OFS) {
            return new OFSFileSystem(properties);
        } else if (type == StorageBackend.StorageType.JFS) {
            return new JFSFileSystem(properties);
        } else if (type == StorageBackend.StorageType.BROKER) {
            return new BrokerFileSystem(name, properties);
        } else {
            throw new UnsupportedOperationException(type.toString() + "backend is not implemented");
        }
    }

    public static RemoteFileSystem getByLocation(String location, Configuration conf) {
        // TODO: need optimize the method. the conf is converted many times.
        Map<String, String> properties = new HashMap<>();
        conf.iterator().forEachRemaining(e -> properties.put(e.getKey(), e.getValue()));
        if (location.startsWith(FeConstants.FS_PREFIX_S3) || location.startsWith(FeConstants.FS_PREFIX_OBS)) {
            return new S3FileSystem(properties);
        } else if (location.startsWith(FeConstants.FS_PREFIX_HDFS) || location.startsWith(FeConstants.FS_PREFIX_GFS)) {
            return new DFSFileSystem(properties);
        } else if (location.startsWith(FeConstants.FS_PREFIX_OFS)) {
            return new OFSFileSystem(properties);
        } else if (location.startsWith(FeConstants.FS_PREFIX_JFS)) {
            return new JFSFileSystem(properties);
        }
        throw new UnsupportedOperationException("Can not create file system for: " + location);
    }

    public static RemoteFileSystem getS3FileSystem(Map<String, String> properties) {
        // use for test
        return get(StorageBackend.StorageType.S3.name(), StorageBackend.StorageType.S3, properties);
    }

    public static org.apache.hadoop.fs.FileSystem getNativeByPath(Path path, Configuration conf) throws IOException {
        return path.getFileSystem(conf);
    }
}
