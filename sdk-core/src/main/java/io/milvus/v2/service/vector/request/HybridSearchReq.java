/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.milvus.v2.service.vector.request;

import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.ranker.BaseRanker;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@SuperBuilder
public class HybridSearchReq
{
    private String databaseName;
    private String collectionName;
    private List<String> partitionNames;
    private List<AnnSearchReq> searchRequests;
    private BaseRanker ranker;
    @Builder.Default
    @Deprecated
    private int topK = 0; // deprecated, replaced by "limit"
    @Builder.Default
    private long limit = 0L;
    private List<String> outFields;
    private long offset;
    @Builder.Default
    private int roundDecimal = -1;
    @Builder.Default
    private ConsistencyLevel consistencyLevel = null;

    private String groupByFieldName;
    private Integer groupSize;
    private Boolean strictGroupSize;

    public static abstract class HybridSearchReqBuilder<C extends HybridSearchReq, B extends HybridSearchReq.HybridSearchReqBuilder<C, B>> {
        // topK is deprecated, topK and limit must be the same value
        public B topK(int val) {
            this.topK$value = val;
            this.topK$set = true;
            this.limit$value = val;
            this.limit$set = true;
            return self();
        }

        public B limit(long val) {
            this.topK$value = (int)val;
            this.topK$set = true;
            this.limit$value = val;
            this.limit$set = true;
            return self();
        }
    }
}
