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

package io.milvus.orm.iterator;


import com.google.common.collect.Lists;
import com.google.gson.reflect.TypeToken;
import io.milvus.common.utils.ExceptionUtils;
import io.milvus.common.utils.JsonUtils;
import io.milvus.exception.ParamException;
import io.milvus.grpc.*;
import io.milvus.param.Constant;
import io.milvus.param.MetricType;
import io.milvus.param.ParamUtils;
import io.milvus.param.collection.FieldType;
import io.milvus.param.dml.SearchIteratorParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.vector.request.SearchIteratorReq;
import io.milvus.v2.utils.RpcUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.*;

import static io.milvus.param.Constant.DEFAULT_SEARCH_EXTENSION_RATE;
import static io.milvus.param.Constant.EF;
import static io.milvus.param.Constant.MAX_BATCH_SIZE;
import static io.milvus.param.Constant.MAX_FILTERED_IDS_COUNT_ITERATION;
import static io.milvus.param.Constant.MAX_TRY_TIME;
import static io.milvus.param.Constant.NO_CACHE_ID;
import static io.milvus.param.Constant.RADIUS;
import static io.milvus.param.Constant.RANGE_FILTER;
import static io.milvus.param.Constant.UNLIMITED;

public class SearchIterator {
    private static final Logger logger = LoggerFactory.getLogger(SearchIterator.class);
    private final IteratorCache iteratorCache;
    private final MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub;
    private final FieldType primaryField;

    private final SearchIteratorParam searchIteratorParam;
    private final int batchSize;
    private final long topK;
    private final String expr;
    private final String metricType;

    private int cacheId;
    private boolean initSuccess;
    private int returnedCount;
    private float width;
    private float tailBand;

    private List<Object> filteredIds;
    private Float filteredDistance = null;
    private Map<String, Object> params;
    private final RpcUtils rpcUtils;
    private long sessionTs = 0;

    public SearchIterator(SearchIteratorParam searchIteratorParam,
                          MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub,
                          FieldType primaryField) {
        this.iteratorCache = new IteratorCache();
        this.searchIteratorParam = searchIteratorParam;
        this.blockingStub = blockingStub;
        this.primaryField = primaryField;
        this.metricType = searchIteratorParam.getMetricType();

        this.batchSize = (int) searchIteratorParam.getBatchSize();
        this.expr = searchIteratorParam.getExpr();
        this.topK = searchIteratorParam.getTopK();
        this.rpcUtils = new RpcUtils();

        initParams();
        checkForSpecialIndexParam();
        checkRmRangeSearchParameters();
        initSearchIterator();
    }

    // to support V2
    public SearchIterator(SearchIteratorReq searchIteratorReq,
                          MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub,
                          CreateCollectionReq.FieldSchema primaryField) {
        this.iteratorCache = new IteratorCache();
        this.blockingStub = blockingStub;
        IteratorAdapterV2 adapter = new IteratorAdapterV2();
        this.searchIteratorParam = adapter.convertV2Req(searchIteratorReq);
        this.primaryField = adapter.convertV2Field(primaryField);
        this.metricType = this.searchIteratorParam.getMetricType();

        this.batchSize = (int) this.searchIteratorParam.getBatchSize();
        this.expr = this.searchIteratorParam.getExpr();
        this.topK = this.searchIteratorParam.getTopK();
        this.rpcUtils = new RpcUtils();

        initParams();
        checkForSpecialIndexParam();
        checkRmRangeSearchParameters();
        initSearchIterator();
    }

    public List<QueryResultsWrapper.RowRecord> next() {
        // 0. check reached limit
        if (!initSuccess || checkReachedLimit()) {
            return Lists.newArrayList();
        }
        long retLen = batchSize;
        if (topK != UNLIMITED) {
            long leftLen = topK - returnedCount;
            retLen = Math.min(leftLen, retLen);
        }

        // 1. if cached page is sufficient, directly return
        List<QueryResultsWrapper.RowRecord> retPage;
        if (isCacheEnough(retLen)) {
            retPage = extractPageFromCache(retLen);
            returnedCount += retPage.size();
            return retPage;
        }

        // 2. if cached page not enough, try to fill the result by probing with constant width
        // until finish filling or exceeding max retry time: 20
        List<QueryResultsWrapper.RowRecord> newPage = trySearchFill();
        int cachedPageLen = pushNewPageToCache(newPage);
        retLen = Math.min(cachedPageLen, retLen);
        retPage = extractPageFromCache(retLen);
        if (retPage.size() == batchSize) {
            updateWidth(retPage);
        }

        // 3. if all result has return, clear the filteredIds
        if (retPage.isEmpty()) {
            filteredIds.clear();
        }

        returnedCount += retLen;
        return retPage;
    }

    public void close() {
        iteratorCache.releaseCache(cacheId);
    }

    private void initParams() {
        if (null != searchIteratorParam.getParams() && !searchIteratorParam.getParams().isEmpty()) {
            params = new HashMap<>();
        }
        params = JsonUtils.fromJson(searchIteratorParam.getParams(), new TypeToken<Map<String, Object>>() {}.getType());
    }

    private void checkForSpecialIndexParam() {
        if (params.containsKey(EF) && (int) params.get(EF) < batchSize) {
            ExceptionUtils.throwUnExpectedException("When using hnsw index, provided ef must be larger than or equal to batch size");
        }
    }

    private void checkRmRangeSearchParameters() {
        if (params.containsKey(RADIUS) && params.containsKey(RANGE_FILTER)) {
            float radius = getFloatValue(RADIUS);
            float rangeFilter = getFloatValue(RANGE_FILTER);
            if (metricsPositiveRelated(metricType) && radius <= rangeFilter) {
                String msg = String.format("for metrics:%s, radius must be larger than range_filter, please adjust your parameter", metricType);
                ExceptionUtils.throwUnExpectedException(msg);
            }
            if (!metricsPositiveRelated(metricType) && radius >= rangeFilter) {
                String msg = String.format("for metrics:%s, radius must be smalled than range_filter, please adjust your parameter", metricType);
                ExceptionUtils.throwUnExpectedException(msg);
            }
        }
    }

    private void initSearchIterator() {
        SearchResults response = executeSearch(params, expr, false, 0L);
        if (response.getSessionTs() <= 0) {
            logger.warn("Failed to get mvccTs from milvus server, use client-side ts instead");
            // fall back to latest session ts by local time
            long ts = System.currentTimeMillis() + 1000L;
            this.sessionTs = ts << 18;
        } else {
            this.sessionTs = response.getSessionTs();
        }

        SearchResultsWrapper searchResultsWrapper = new SearchResultsWrapper(response.getResults());
        List<QueryResultsWrapper.RowRecord> result = searchResultsWrapper.getRowRecords(0);
        if (CollectionUtils.isEmpty(result)) {
            String msg = "Cannot init search iterator because init page contains no matched rows, " +
                    "please check the radius and range_filter set up by searchParams";
            logger.error(msg);

            cacheId = NO_CACHE_ID;
            initSuccess = false;
            return;
        }
        cacheId = iteratorCache.cache(NO_CACHE_ID, result);

        setUpRangeParameters(result);
        updateFilteredIds(searchResultsWrapper);
        initSuccess = true;
    }

    private void setUpRangeParameters(List<QueryResultsWrapper.RowRecord> page) {
        updateWidth(page);
        QueryResultsWrapper.RowRecord lastHit = page.get(page.size() - 1);
        tailBand = getDistance(lastHit);
        String msg = String.format("set up init parameter for searchIterator width:%s tail_band:%s", width, tailBand);
        logger.debug(msg);
//        System.out.println(msg);
    }

    private void updateFilteredIds(SearchResultsWrapper searchResultsWrapper) {
        List<SearchResultsWrapper.IDScore> idScores = searchResultsWrapper.getIDScore(0);
        if (CollectionUtils.isEmpty(idScores)) {
            return;
        }

        SearchResultsWrapper.IDScore lastHit = idScores.get(idScores.size() - 1);
        if (lastHit == null) {
            return;
        }

        if (filteredDistance == null || lastHit.getScore() != filteredDistance) {
            // distance has changed, clear filter_ids array
            filteredIds = Lists.newArrayList();
            // renew the distance for filtering
            filteredDistance = lastHit.getScore();
        }

        // update filter ids to avoid returning result repeatedly
        for (SearchResultsWrapper.IDScore hit : idScores) {
            if (hit.getScore() == lastHit.getScore()) {
                if (primaryField.getDataType() == DataType.VarChar) {
                    filteredIds.add(hit.getStrID());
                } else {
                    filteredIds.add(hit.getLongID());
                }
            }
        }

        if (filteredIds.size() > MAX_FILTERED_IDS_COUNT_ITERATION) {
            String msg = String.format("filtered ids length has accumulated to more than %s, " +
                    "there is a danger of overly memory consumption", MAX_FILTERED_IDS_COUNT_ITERATION);
            ExceptionUtils.throwUnExpectedException(msg);
        }
    }

    private SearchResults executeSearch(Map<String, Object> params, String nextExpr, boolean toExtendBatch, long ts) {
        SearchParam.Builder searchParamBuilder = SearchParam.newBuilder()
                .withDatabaseName(searchIteratorParam.getDatabaseName())
                .withCollectionName(searchIteratorParam.getCollectionName())
                .withPartitionNames(searchIteratorParam.getPartitionNames())
                .withConsistencyLevel(searchIteratorParam.getConsistencyLevel())
                .withVectorFieldName(searchIteratorParam.getVectorFieldName())
                .withTopK(extendBatchSize(batchSize, toExtendBatch, params))
                .withExpr(nextExpr)
                .withOutFields(searchIteratorParam.getOutFields())
                .withRoundDecimal(searchIteratorParam.getRoundDecimal())
                .withParams(JsonUtils.toJson(params))
                .withMetricType(MetricType.valueOf(searchIteratorParam.getMetricType()))
                .withIgnoreGrowing(searchIteratorParam.isIgnoreGrowing());

        if (StringUtils.isNotEmpty(searchIteratorParam.getGroupByFieldName())) {
            searchParamBuilder.withGroupByFieldName(searchIteratorParam.getGroupByFieldName());
        }
        fillVectorsByPlType(searchParamBuilder);

        SearchRequest searchRequest = ParamUtils.convertSearchParam(searchParamBuilder.build());
        SearchRequest.Builder builder = searchRequest.toBuilder();
        // iterator
        builder.addSearchParams(
                KeyValuePair.newBuilder()
                        .setKey(Constant.ITERATOR_FIELD)
                        .setValue(String.valueOf(Boolean.TRUE))
                        .build());

        // pass the session ts to search interface
        builder.setGuaranteeTimestamp(ts).build();

        // set default consistency level
        builder.setUseDefaultConsistency(true);

        SearchResults response = rpcUtils.retry(()->blockingStub.search(builder.build()));
        String title = String.format("SearchRequest collectionName:%s", searchIteratorParam.getCollectionName());
        rpcUtils.handleResponse(title, response.getStatus());
        return response;
    }

    private void fillVectorsByPlType(SearchParam.Builder searchParamBuilder) {
        switch (searchIteratorParam.getPlType()) {
            case FloatVector:
                searchParamBuilder.withFloatVectors((List<List<Float>>) searchIteratorParam.getVectors());
                break;
            case BinaryVector:
                searchParamBuilder.withBinaryVectors((List<ByteBuffer>) searchIteratorParam.getVectors());
                break;
            case Float16Vector:
                searchParamBuilder.withFloat16Vectors((List<ByteBuffer>) searchIteratorParam.getVectors());
                break;
            case BFloat16Vector:
                searchParamBuilder.withBFloat16Vectors((List<ByteBuffer>) searchIteratorParam.getVectors());
                break;
            case SparseFloatVector:
                searchParamBuilder.withSparseFloatVectors((List<SortedMap<Long, Float>>) searchIteratorParam.getVectors());
                break;
            default:
                searchParamBuilder.withVectors(searchIteratorParam.getVectors());
                break;
        }
    }

    private int extendBatchSize(int batchSize, boolean toExtendBatchSize, Map<String, Object> nextParams) {
        int extendRate = 1;

        if (toExtendBatchSize) {
            extendRate = DEFAULT_SEARCH_EXTENSION_RATE;
        }

        if (nextParams.containsKey(EF)) {
            int ef = (int) nextParams.get(EF);
            int realBatch = Math.min(MAX_BATCH_SIZE, Math.min(batchSize * extendRate, ef));
            if (ef > realBatch) {
                nextParams.put(EF, realBatch);
            }
            return realBatch;
        }
        return Math.min(MAX_BATCH_SIZE, batchSize * extendRate);
    }

    private void updateWidth(List<QueryResultsWrapper.RowRecord> page) {
        QueryResultsWrapper.RowRecord firstHit = page.get(0);
        QueryResultsWrapper.RowRecord lastHit = page.get(page.size() - 1);

        if (metricsPositiveRelated(metricType)) {
            width = getDistance(lastHit) - getDistance(firstHit);
        } else {
            width = getDistance(firstHit) - getDistance(lastHit);
        }

        if (width == 0.0) {
            // enable a minimum value for width to avoid radius and range_filter equal error
            width = 0.05f;
        }
    }

    private boolean metricsPositiveRelated(String metricType) {
        if (Lists.newArrayList(MetricType.L2.name(), MetricType.JACCARD.name(), MetricType.HAMMING.name()).contains(metricType)) {
            return true;
        }
        if (Lists.newArrayList(MetricType.IP.name(), MetricType.COSINE.name()).contains(metricType)) {
            return false;
        }
        String msg = String.format("unsupported metrics type for search iteration: %s", metricType);
        ExceptionUtils.throwUnExpectedException(msg);
        return false;
    }

    private boolean checkReachedLimit() {
        if (topK == UNLIMITED || returnedCount < topK) {
            return false;
        }

        String msg = String.format("reached search limit:%s, returned_count:%s, directly return", topK, returnedCount);
        logger.debug(msg);

        return true;
    }

    private boolean isCacheEnough(long count) {
        List<QueryResultsWrapper.RowRecord> cachedPage = iteratorCache.fetchCache(cacheId);
        return cachedPage != null && cachedPage.size() >= count;
    }

    private List<QueryResultsWrapper.RowRecord> extractPageFromCache(long count) {
        List<QueryResultsWrapper.RowRecord> cachedPage = iteratorCache.fetchCache(cacheId);
        if (cachedPage == null || cachedPage.size() < count) {
            String msg = String.format("Wrong, try to extract %s result from cache, more than %s there must be sth wrong with code",
                    count, cachedPage == null ? 0 : cachedPage.size());
            throw new ParamException(msg);
        }

        List<QueryResultsWrapper.RowRecord> retPageRes = cachedPage.subList(0, (int)count);
        List<QueryResultsWrapper.RowRecord> leftCachePage = cachedPage.subList((int)count, cachedPage.size());

        iteratorCache.cache(cacheId, leftCachePage);
        return retPageRes;
    }

    private List<QueryResultsWrapper.RowRecord> trySearchFill() {
        List<QueryResultsWrapper.RowRecord> finalPage = Lists.newArrayList();
        int tryTime = 0;
        int coefficient = 1;

        while (true) {
            Map<String, Object> nextParams = nextParams(coefficient);
            String nextExpr = filteredDuplicatedResultExpr(expr);
            SearchResults response = executeSearch(nextParams, nextExpr, true, this.sessionTs);
            SearchResultsWrapper searchResultsWrapper = new SearchResultsWrapper(response.getResults());
            updateFilteredIds(searchResultsWrapper);
            List<QueryResultsWrapper.RowRecord> newPage = searchResultsWrapper.getRowRecords(0);

            tryTime++;
            if (!newPage.isEmpty()) {
                finalPage.addAll(newPage);
                tailBand = getDistance(newPage.get(newPage.size() - 1));
            }

            if (finalPage.size() >= batchSize) {
                break;
            }

            if (tryTime > MAX_TRY_TIME) {
                String msg = String.format("Search exceed max try times:%s directly break", MAX_TRY_TIME);
                logger.warn(msg);
                break;
            }
            // if there's a ring containing no vectors matched, then we need to extend
            // the ring continually to avoid empty ring problem
            coefficient++;
        }
        return finalPage;
    }

    private Map<String, Object> nextParams(int coefficient) {
        coefficient = Math.max(1, coefficient);

        // Note: params is a Map<String, Object> to store the original parameters, we must not change the params.
        // here we make a new object nextParams, to ensure is it a deep copy, we convert it to JSON string and
        // convert back to a Map<String, Object>.
        Map<String, Object> nextParams = JsonUtils.fromJson(JsonUtils.toJson(params),
                new TypeToken<Map<String, Object>>() {}.getType());

        if (metricsPositiveRelated(metricType)) {
            float nextRadius = tailBand + width * coefficient;
            if (params.containsKey(RADIUS) && nextRadius > getFloatValue(RADIUS)) {
                nextParams.put(RADIUS, getFloatValue(RADIUS));
            } else {
                nextParams.put(RADIUS, nextRadius);
            }
        } else {
            double nextRadius = tailBand - width * coefficient;
            if (params.containsKey(RADIUS) && nextRadius < getFloatValue(RADIUS)) {
                nextParams.put(RADIUS, getFloatValue(RADIUS));
            } else {
                nextParams.put(RADIUS, nextRadius);
            }
        }
        nextParams.put(RANGE_FILTER, tailBand);

        String msg = String.format("next round search iteration radius:%s,range_filter:%s,coefficient:%s",
                convertToStr(nextParams.get(RADIUS)), convertToStr(nextParams.get(RANGE_FILTER)), coefficient);
        logger.debug(msg);
        return nextParams;
    }

    private String filteredDuplicatedResultExpr(String expr) {
        if (CollectionUtils.isEmpty(filteredIds)) {
            return expr;
        }

        StringBuilder filteredIdsStr = new StringBuilder();
        for (Object filteredId : filteredIds) {
            if (primaryField.getDataType() == DataType.VarChar) {
                filteredIdsStr.append("\"").append(filteredId.toString()).append("\",");
            } else {
                filteredIdsStr.append((long) filteredId).append(",");
            }
        }
        filteredIdsStr = new StringBuilder(filteredIdsStr.substring(0, filteredIdsStr.length() - 1));

        if (filteredIdsStr.length() > 0) {
            if (expr != null && !expr.isEmpty()) {
                String filterExpr = String.format(" and %s not in [%s]", primaryField.getName(), filteredIdsStr);
                return expr + filterExpr;
            }
            return String.format("%s not in [%s]", primaryField.getName(), filteredIdsStr);
        }
        return expr;
    }

    private int pushNewPageToCache(List<QueryResultsWrapper.RowRecord> page) {
        if (page == null) {
            throw new ParamException("Cannot push None page into cache");
        }

        List<QueryResultsWrapper.RowRecord> cachedPage = iteratorCache.fetchCache(cacheId);
        if (cachedPage == null) {
            iteratorCache.cache(cacheId, page);
            cachedPage = page;
        } else {
            cachedPage.addAll(page);
        }
        return cachedPage.size();
    }

    private float getDistance(QueryResultsWrapper.RowRecord record) {
        return (float) record.get("score");
    }

    // Avoiding precision loss when converting to exponent notation.
    private String convertToStr(Object value) {
        DecimalFormat df = new DecimalFormat("0.0");
        return df.format(value);
    }

    private float getFloatValue(String key) {
        return ((Double) params.get(key)).floatValue();
    }
}
