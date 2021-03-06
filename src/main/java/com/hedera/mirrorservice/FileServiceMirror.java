package com.hedera.mirrorservice;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.hederahashgraph.service.proto.java.FileServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.tuple.Pair;

@Log4j2
public class FileServiceMirror extends FileServiceGrpc.FileServiceImplBase {

	/**
	 * The mirror node provides the same service interface as Hedera node to clients;
	 * Clients can build a channel to the mirror node and call service methods remotely;
	 * The mirror node accepts Transactions, extract the nodeAccountID in each Transaction, build a channel to that Hedera node, call its service methods, get the TransactionResponse, and return to clients.
	 * @param request
	 * @param responseObserver
	 * @param methodName
	 */
	static void rpc_FileService(Transaction request,
			StreamObserver<TransactionResponse> responseObserver,
			String methodName) {
		try {
			AccountID nodeAccountID = ServiceAgent.extractNodeAccountID(request);
			Pair<FileServiceGrpc.FileServiceBlockingStub, ManagedChannel> pair = ServiceAgent.getFileServiceStub(nodeAccountID);
			ServiceAgent.rpcHelper_Tx(pair.getLeft(), pair.getRight(),
					request, responseObserver, methodName);

		} catch (InvalidProtocolBufferException ex) {
			log.error("Error parsing transaction body", ex);
		}
	}

	/**
	 * The mirror node provides the same service interface as Hedera node to clients;
	 * Clients can build a channel to the mirror node and call service methods remotely;
	 * The mirror node accepts Queries, extract the AccountID in payment in the QueryHeader of each Query, build a channel to that Hedera node, call its service methods, get the Response, and return to clients.
	 * If the Query doesn't contain any payment, we send it to the default node
	 * @param request
	 * @param responseObserver
	 * @param methodName
	 */
	static void rpc_FileService(Query request,
			StreamObserver<Response> responseObserver,
			String methodName) {
		AccountID accountID = ServiceAgent.extractNodeAccountID(request);
		if (accountID == null) {
			log.error("Missing nodeAccountID, Query = {}", request);
			return;
		}
		Pair<FileServiceGrpc.FileServiceBlockingStub, ManagedChannel> pair = ServiceAgent.getFileServiceStub(accountID);
		ServiceAgent.rpcHelper_Query(pair.getLeft(), pair.getRight(),
				request, responseObserver, methodName);
	}

	/**
	 * <pre>
	 * Creates a file with the content by submitting the transaction. The grpc server returns the TransactionResponse
	 * </pre>
	 */
	@Override
	public void createFile(Transaction request,
			StreamObserver<TransactionResponse> responseObserver) {
		rpc_FileService(request, responseObserver, "createFile");
	}

	/**
	 * <pre>
	 * Updates a file by submitting the transaction. The grpc server returns the TransactionResponse
	 * </pre>
	 */
	@Override
	public void updateFile(Transaction request,
			StreamObserver<TransactionResponse> responseObserver) {
		rpc_FileService(request, responseObserver, "updateFile");
	}

	/**
	 * <pre>
	 * Deletes a file by submitting the transaction. The grpc server returns the TransactionResponse
	 * </pre>
	 */
	@Override
	public void deleteFile(Transaction request,
			StreamObserver<TransactionResponse> responseObserver) {
		rpc_FileService(request, responseObserver, "deleteFile");
	}

	/**
	 * <pre>
	 * Appends the file contents(for a given FileID) by submitting the transaction. The grpc server returns the TransactionResponse
	 * </pre>
	 */
	@Override
	public void appendContent(Transaction request,
			StreamObserver<TransactionResponse> responseObserver) {
		rpc_FileService(request, responseObserver, "appendContent");
	}

	/**
	 * <pre>
	 * Retrieves the file content by submitting the query. The grpc server returns the Response
	 * </pre>
	 */
	@Override
	public void getFileContent(Query request,
			StreamObserver<Response> responseObserver) {
		rpc_FileService(request, responseObserver, "getFileContent");
	}

	/**
	 * <pre>
	 * Retrieves the file information by submitting the query. The grpc server returns the Response
	 * </pre>
	 */
	@Override
	public void getFileInfo(Query request,
			StreamObserver<Response> responseObserver) {
		rpc_FileService(request, responseObserver, "getFileInfo");
	}

	/**
	 * <pre>
	 * Deletes a file by submitting the transaction when the account has admin privileges on the file. The grpc server returns the TransactionResponse
	 * </pre>
	 */
	@Override
	public void systemDelete(com.hederahashgraph.api.proto.java.Transaction request,
			io.grpc.stub.StreamObserver<com.hederahashgraph.api.proto.java.TransactionResponse> responseObserver) {
		rpc_FileService(request, responseObserver, "systemDelete");
	}

	/**
	 * <pre>
	 * UnDeletes a file by submitting the transaction when the account has admin privileges on the file. The grpc server returns the TransactionResponse
	 * </pre>
	 */
	@Override
	public void systemUndelete(com.hederahashgraph.api.proto.java.Transaction request,
			io.grpc.stub.StreamObserver<com.hederahashgraph.api.proto.java.TransactionResponse> responseObserver) {
		rpc_FileService(request, responseObserver, "systemUndelete");
	}
}
