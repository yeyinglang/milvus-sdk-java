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
package io.milvus.v1;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.control.ManualCompactParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.*;
import io.milvus.param.partition.*;
import io.milvus.response.*;

import java.util.*;
import java.util.concurrent.TimeUnit;


public class GeneralExample {
    private static final MilvusClient milvusClient;

    static {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost("localhost")
                .withPort(19530)
                .withAuthorization("root","Milvus")
                .build();
        RetryParam retryParam = RetryParam.newBuilder()
                .withMaxRetryTimes(3)
                .build();
        milvusClient = new MilvusServiceClient(connectParam).withRetry(retryParam);
    }

    private static final String COLLECTION_NAME = "java_sdk_example_general_v1";
    private static final String ID_FIELD = "userID";
    private static final String VECTOR_FIELD = "userFace";
    private static final Integer VECTOR_DIM = 64;
    private static final String AGE_FIELD = "userAge";

    private static final String INDEX_NAME = "userFaceIndex";
    private static final IndexType INDEX_TYPE = IndexType.IVF_FLAT;
    private static final String INDEX_PARAM = "{\"nlist\":128}";

    private static final Long SEARCH_K = 5L;
    private static final String SEARCH_PARAM = "{\"nprobe\":10}";


    private R<RpcStatus> createCollection(long timeoutMilliseconds) {
        System.out.println("========== createCollection() ==========");
        FieldType fieldType1 = FieldType.newBuilder()
                .withName(ID_FIELD)
                .withDescription("user identification")
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(true)
                .build();

        FieldType fieldType2 = FieldType.newBuilder()
                .withName(VECTOR_FIELD)
                .withDescription("face embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(VECTOR_DIM)
                .build();

        FieldType fieldType3 = FieldType.newBuilder()
                .withName(AGE_FIELD)
                .withDescription("user age")
                .withDataType(DataType.Int8)
                .build();

        CreateCollectionParam createCollectionReq = CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withDescription("customer info")
                .withShardsNum(2)
                .withEnableDynamicField(false)
                .addFieldType(fieldType1)
                .addFieldType(fieldType2)
                .addFieldType(fieldType3)
                .build();
        R<RpcStatus> response = milvusClient.withTimeout(timeoutMilliseconds, TimeUnit.MILLISECONDS)
                .createCollection(createCollectionReq);
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<RpcStatus> dropCollection() {
        System.out.println("========== dropCollection() ==========");
        R<RpcStatus> response = milvusClient.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        System.out.println(response);
        return response;
    }

    private boolean hasCollection() {
        System.out.println("========== hasCollection() ==========");
        R<Boolean> response = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response.getData();
    }

    private R<RpcStatus> loadCollection() {
        System.out.println("========== loadCollection() ==========");
        R<RpcStatus> response = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<RpcStatus> releaseCollection() {
        System.out.println("========== releaseCollection() ==========");
        R<RpcStatus> response = milvusClient.releaseCollection(ReleaseCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<DescribeCollectionResponse> describeCollection() {
        System.out.println("========== describeCollection() ==========");
        R<DescribeCollectionResponse> response = milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        DescCollResponseWrapper wrapper = new DescCollResponseWrapper(response.getData());
        System.out.println(wrapper);
        return response;
    }

    private R<GetCollectionStatisticsResponse> getCollectionStatistics() {
        // call flush() to flush the insert buffer to storage,
        // so that the getCollectionStatistics() can get correct number
        milvusClient.flush(FlushParam.newBuilder().addCollectionName(COLLECTION_NAME).build());

        System.out.println("========== getCollectionStatistics() ==========");
        R<GetCollectionStatisticsResponse> response = milvusClient.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build());
        CommonUtils.handleResponseStatus(response);
        GetCollStatResponseWrapper wrapper = new GetCollStatResponseWrapper(response.getData());
        System.out.println("Collection row count: " + wrapper.getRowCount());
        return response;
    }

    private R<ShowCollectionsResponse> showCollections() {
        System.out.println("========== showCollections() ==========");
        R<ShowCollectionsResponse> response = milvusClient.showCollections(ShowCollectionsParam.newBuilder()
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<RpcStatus> createPartition(String partitionName) {
        System.out.println("========== createPartition() ==========");
        R<RpcStatus> response = milvusClient.createPartition(CreatePartitionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withPartitionName(partitionName)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<RpcStatus> dropPartition(String partitionName) {
        System.out.println("========== dropPartition() ==========");
        R<RpcStatus> response = milvusClient.dropPartition(DropPartitionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withPartitionName(partitionName)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<Boolean> hasPartition(String partitionName) {
        System.out.println("========== hasPartition() ==========");
        R<Boolean> response = milvusClient.hasPartition(HasPartitionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withPartitionName(partitionName)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<RpcStatus> releasePartition(String partitionName) {
        System.out.println("========== releasePartition() ==========");
        R<RpcStatus> response = milvusClient.releasePartitions(ReleasePartitionsParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .addPartitionName(partitionName)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<ShowPartitionsResponse> showPartitions() {
        System.out.println("========== showPartitions() ==========");
        R<ShowPartitionsResponse> response = milvusClient.showPartitions(ShowPartitionsParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<RpcStatus> createIndex() {
        System.out.println("========== createIndex() ==========");
        // create index for scalar field
        R<RpcStatus> response = milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFieldName(AGE_FIELD)
                .withIndexType(IndexType.STL_SORT)
                .withSyncMode(Boolean.TRUE)
                .build());
        CommonUtils.handleResponseStatus(response);

        // create index for vector field
        response = milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFieldName(VECTOR_FIELD)
                .withIndexName(INDEX_NAME)
                .withIndexType(INDEX_TYPE)
                .withMetricType(MetricType.L2)
                .withExtraParam(INDEX_PARAM)
                .withSyncMode(Boolean.TRUE)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<RpcStatus> dropIndex() {
        System.out.println("========== dropIndex() ==========");
        R<RpcStatus> response = milvusClient.dropIndex(DropIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withIndexName(INDEX_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<DescribeIndexResponse> describeIndex() {
        System.out.println("========== describeIndex() ==========");
        R<DescribeIndexResponse> response = milvusClient.describeIndex(DescribeIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withIndexName(INDEX_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<GetIndexStateResponse> getIndexState() {
        System.out.println("========== getIndexState() ==========");
        R<GetIndexStateResponse> response = milvusClient.getIndexState(GetIndexStateParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withIndexName(INDEX_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<GetIndexBuildProgressResponse> getIndexBuildProgress() {
        System.out.println("========== getIndexBuildProgress() ==========");
        R<GetIndexBuildProgressResponse> response = milvusClient.getIndexBuildProgress(
                GetIndexBuildProgressParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withIndexName(INDEX_NAME)
                        .build());
        CommonUtils.handleResponseStatus(response);
        System.out.println(response);
        return response;
    }

    private R<MutationResult> delete(String partitionName, String expr) {
        System.out.println("========== delete() ==========");
        DeleteParam build = DeleteParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withPartitionName(partitionName)
                .withExpr(expr)
                .build();
        R<MutationResult> response = milvusClient.delete(build);
        CommonUtils.handleResponseStatus(response);
        System.out.println(response.getData());
        return response;
    }

    private R<SearchResults> searchFace(String expr) {
        System.out.println("========== searchFace() ==========");
        long begin = System.currentTimeMillis();

        List<String> outFields = Collections.singletonList(AGE_FIELD);
        List<List<Float>> vectors = CommonUtils.generateFloatVectors(VECTOR_DIM, 5);

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withMetricType(MetricType.L2)
                .withOutFields(outFields)
                .withLimit(SEARCH_K)
                .withFloatVectors(vectors)
                .withVectorFieldName(VECTOR_FIELD)
                .withExpr(expr)
                .withParams(SEARCH_PARAM)
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .build();

        R<SearchResults> response = milvusClient.search(searchParam);
        long end = System.currentTimeMillis();
        long cost = (end - begin);
        System.out.println("Search time cost: " + cost + "ms");

        CommonUtils.handleResponseStatus(response);
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        for (int i = 0; i < vectors.size(); ++i) {
            System.out.println("Search result of No." + i);
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(i);
            for (SearchResultsWrapper.IDScore score : scores) {
                System.out.println(score);
            }
        }

        return response;
    }

    private R<QueryResults> query(String expr) {
        System.out.println("========== query() ==========");
        List<String> fields = Arrays.asList(ID_FIELD, AGE_FIELD);
        QueryParam test = QueryParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withExpr(expr)
                .withOutFields(fields)
                .withLimit(10L)
                .build();
        R<QueryResults> response = milvusClient.query(test);
        CommonUtils.handleResponseStatus(response);
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        System.out.println(ID_FIELD + ":" + wrapper.getFieldWrapper(ID_FIELD).getFieldData().toString());
        System.out.println(AGE_FIELD + ":" + wrapper.getFieldWrapper(AGE_FIELD).getFieldData().toString());
        System.out.println("Query row count: " + wrapper.getFieldWrapper(ID_FIELD).getRowCount());
        return response;
    }

    private R<ManualCompactionResponse> compact() {
        System.out.println("========== compact() ==========");
        R<ManualCompactionResponse> response = milvusClient.manualCompact(ManualCompactParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .build());
        CommonUtils.handleResponseStatus(response);
        return response;
    }

    private R<MutationResult> insertColumns(String partitionName, int count) {
        System.out.println("========== insertColumns() ==========");
        List<List<Float>> vectors = CommonUtils.generateFloatVectors(VECTOR_DIM, count);

        Random ran = new Random();
        List<Integer> ages = new ArrayList<>();
        for (long i = 0L; i < count; ++i) {
            ages.add(ran.nextInt(99));
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(AGE_FIELD, ages));
        fields.add(new InsertParam.Field(VECTOR_FIELD, vectors));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withPartitionName(partitionName)
                .withFields(fields)
                .build();

        R<MutationResult> response = milvusClient.insert(insertParam);
        CommonUtils.handleResponseStatus(response);
        return response;
    }

    private R<MutationResult> insertRows(String partitionName, int count) {
        System.out.println("========== insertRows() ==========");

        List<JsonObject> rowsData = new ArrayList<>();
        Random ran = new Random();
        Gson gson = new Gson();
        for (long i = 0L; i < count; ++i) {
            JsonObject row = new JsonObject();
            row.addProperty(AGE_FIELD, ran.nextInt(99));
            row.add(VECTOR_FIELD, gson.toJsonTree(CommonUtils.generateFloatVector(VECTOR_DIM)));

            rowsData.add(row);
        }

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withPartitionName(partitionName)
                .withRows(rowsData)
                .build();

        R<MutationResult> response = milvusClient.insert(insertParam);
        CommonUtils.handleResponseStatus(response);
        return response;
    }

    public static void main(String[] args) {
        GeneralExample example = new GeneralExample();

        if (example.hasCollection()) {
            example.dropCollection();
        }
        example.createCollection(2000);
        example.hasCollection();
        example.describeCollection();
        example.showCollections();

        final String partitionName = "p1";
        example.createPartition(partitionName);
        example.hasPartition(partitionName);
        example.showPartitions();

        final int row_count = 10000;
        List<Long> deleteIds = new ArrayList<>();
        Random ran = new Random();
        for (int i = 0; i < 100; ++i) {
            // insertColumns
            R<MutationResult> result = example.insertColumns(partitionName, row_count);
            MutationResultWrapper wrapper = new MutationResultWrapper(result.getData());
            List<Long> ids = wrapper.getLongIDs();
            deleteIds.add(ids.get(ran.nextInt(row_count)));
        }

        // insertRows
        R<MutationResult> result = example.insertRows(partitionName, 10);
        MutationResultWrapper wrapper = new MutationResultWrapper(result.getData());
        long insertCount = wrapper.getInsertCount();
        List<Long> longIDs = wrapper.getLongIDs();
        System.out.println("complete insertRows, insertCount:" + insertCount + "; longIDs:" + longIDs);

        example.getCollectionStatistics();

        // Must create an index before load(), FLAT is brute-force search(no index)
        milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withFieldName(VECTOR_FIELD)
                .withIndexName(INDEX_NAME)
                .withIndexType(IndexType.FLAT)
                .withMetricType(MetricType.L2)
                .withExtraParam(INDEX_PARAM)
                .withSyncMode(Boolean.TRUE)
                .build());

        // loadCollection() must be called before search()
        example.loadCollection();

        System.out.println("Search without index");
        String searchExpr = AGE_FIELD + " > 50";
        example.searchFace(searchExpr);

        // must call releaseCollection() before re-create index
        example.releaseCollection();

        // re-create another index
        example.dropIndex();
        example.createIndex();
        example.describeIndex();
        example.getIndexBuildProgress();
        example.getIndexState();

        // loadCollection() must be called before search()
        example.loadCollection();

        System.out.println("Search with index");
        example.searchFace(searchExpr);

        String deleteExpr = ID_FIELD + " in " + deleteIds;
        example.delete(partitionName, deleteExpr);
        String queryExpr = AGE_FIELD + " == 60";
        example.query(queryExpr);

        example.compact();
        example.getCollectionStatistics();

        example.releaseCollection();
        example.dropPartition(partitionName);
        example.dropIndex();
        example.dropCollection();
    }
}