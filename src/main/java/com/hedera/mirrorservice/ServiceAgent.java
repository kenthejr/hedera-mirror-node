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
import com.hedera.mirrorNodeProxy.MirrorNodeProxy;
import com.hedera.utilities.Utility;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.hederahashgraph.service.proto.java.CryptoServiceGrpc;
import com.hederahashgraph.service.proto.java.FileServiceGrpc;
import com.hederahashgraph.service.proto.java.SmartContractServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.StreamObserver;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Log4j2
public class ServiceAgent {

	public static AccountID getDefaultNodeAccountID() {
		return AccountID.newBuilder().setAccountNum(3).build();
	}

	static void logInfo(final Transaction request, final AccountID nodeAccountID,
			final TransactionResponse response, final String methodName) throws InvalidProtocolBufferException {
		if (log.isDebugEnabled()) {
			log.debug("{} Transaction = {} Sending transaction to: {}\n Precheck Result: {}\n",
					methodName,
					Utility.printTransaction(request),
					Utility.printProtoMessage(nodeAccountID),
					response.getNodeTransactionPrecheckCode());
		}
	}

	static void logInfo(final Query request, AccountID nodeAccountID,
			Response response, final String methodName) {
		if (log.isDebugEnabled()) {
			log.debug("{} Query = {} \n Sending Query to: {}\n Response: {}\n",
					methodName,
					Utility.printProtoMessage(request),
					Utility.printProtoMessage(nodeAccountID),
					Utility.printProtoMessage(response));
		}
	}

	static AccountID extractNodeAccountID(final Transaction transaction) throws InvalidProtocolBufferException {
		return Utility.getTransactionBody(transaction).getNodeAccountID();
	}

	/**
	 * According to HAPI, each query from the client to the node will contain the QueryHeader, which includes a payment for the response. The payment is blank for TransactionGetReceiptQuery. It can also be left blank when the responseType is costAnswer or costAnswerStateProof. But it needs to be filled in for all other cases.
	 * This method is used for checking whether the payment can be blank for a Query which don't have the payment in its QueryHeader
	 * @param query
	 * @param queryHeader
	 * @return
	 */
	static boolean paymentCanBeBlank(Query query, QueryHeader queryHeader) {
		return query.hasTransactionGetReceipt() || queryHeader.getResponseType() == ResponseType.COST_ANSWER || queryHeader.getResponseType() == ResponseType.COST_ANSWER_STATE_PROOF;
	}

	/**
	 * A Query message has a oneof type field,
	 * it can be GetByKeyQuery, GetBySolidityIDQuery, etc.
	 * This method return the specific Query object contained in this Query Message
	 * @param query
	 * @return
	 */
	public static Object getOneOfField(final Query query) {
		return query.getField(query.getDescriptorForType()
				.findFieldByNumber(query.getQueryCase().getNumber()));
	}

	/**
	 * Extract the nodeAccountID in payment in the QueryHeader of each Query,
	 * If the Query doesn't contain any payment, we return the default node AccountID
	 * @param query
	 * @return AccountID
	 */
	public static AccountID extractNodeAccountID(final Query query) {
		QueryHeader queryHeader;
		// Get the specific Query object contained in this Query Message
		Object field = getOneOfField(query);
		// Call getHeader() method of the specific Query object
		try {
			Method getHeaderMethod = field.getClass().getMethod("getHeader");
			queryHeader = (QueryHeader) getHeaderMethod.invoke(field);
			if (queryHeader.hasPayment()) {
				return Utility.getTransactionBody(queryHeader.getPayment()).getNodeAccountID();
			}
			return getDefaultNodeAccountID();

		} catch (Exception ex) {
			log.error("Error extracting node account ID", ex);
		}

		return null;
	}

	static AccountID extractNodeAccountID(final QueryHeader queryHeader) throws InvalidProtocolBufferException {
		if (queryHeader.hasPayment()) {
			return TransactionBody.parseFrom(queryHeader.getPayment().getBodyBytes()).getNodeAccountID();
		}
		return null;
	}

	static Pair<String, Integer> getHostPortForNode(AccountID nodeAccountID) {
		return MirrorNodeProxy.getHostPort(nodeAccountID);
	}

	static ManagedChannel buildManagedChannel(final String host, final int port) {
		return NettyChannelBuilder
				.forAddress(host, port)
				.usePlaintext()
				.build();
	}

	static ManagedChannel buildManagedChannel(final AccountID nodeAccountID) {
		Pair<String, Integer> hostPort = getHostPortForNode(nodeAccountID);
		return NettyChannelBuilder
				.forAddress(hostPort.getKey(), hostPort.getValue())
				.usePlaintext()
				.build();
	}

	/**
	 * call remote service method;
	 * send Transaction;
	 * get the TransactionResponse;
	 * return to clients.
	 * @param stub
	 * @param channel
	 * @param request
	 * @param responseObserver
	 * @param methodName name of the method which we want to call remotely
	 */
	static void rpcHelper_Tx(final AbstractStub stub,
			final ManagedChannel channel,
			final Transaction request,
			final StreamObserver<TransactionResponse> responseObserver,
			final String methodName) {
		try {
			AccountID nodeAccountID = extractNodeAccountID(request);
			Class blockingStubClass = stub.getClass();
			Method method = blockingStubClass.getMethod(methodName, request.getClass());
			// call rpc function and get TransactionResponse
			TransactionResponse response = (TransactionResponse) method.invoke(stub, request);
			// shutdown the ManagedChannel
			channel.shutdown();
			ServiceAgent.logInfo(request, nodeAccountID, response, methodName);
			// return response to Clients
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		} catch (InvalidProtocolBufferException ex) {
			log.error("rpcHelper_Tx : Transaction body parsing exception", ex);
		} catch (NoSuchMethodException ex) {
			log.error("rpcHelper_Tx : NoSuchMethodException", ex);
		} catch (IllegalAccessException ex) {
			log.error("rpcHelper_Tx : IllegalAccessException", ex);
		} catch (InvocationTargetException ex) {
			log.error("rpcHelper_Tx : InvocationTargetException", ex.getCause());
		}
	}

	/**
	 * call remote service method;
	 * send Query;
	 * get the Response;
	 * return to clients.
	 * @param stub
	 * @param channel
	 * @param request
	 * @param responseObserver
	 * @param methodName name of the method which we want to call remotely
	 */

	static void rpcHelper_Query(final AbstractStub stub,
			final ManagedChannel channel,
			final Query request,
			final StreamObserver<Response> responseObserver,
			final String methodName) {
		try {
			AccountID nodeAccountID = extractNodeAccountID(request);
			Class blockingStubClass = stub.getClass();
			Method method = blockingStubClass.getMethod(methodName, request.getClass());
			// call rpc function and get TransactionResponse
			Response response = (Response) method.invoke(stub, request);
			// shutdown the ManagedChannel
			channel.shutdown();
			ServiceAgent.logInfo(request, nodeAccountID, response, methodName);
			// return response to Clients
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		} catch (NoSuchMethodException ex) {
			log.error("rpcHelper_Query : NoSuchMethodException", ex);
		} catch (IllegalAccessException ex) {
			log.error("rpcHelper_Query : IllegalAccessException", ex);
		} catch (InvocationTargetException ex) {
			log.error("rpcHelper_Query : InvocationTargetException", ex);
		}
	}


	static Pair<CryptoServiceGrpc.CryptoServiceBlockingStub, ManagedChannel> getCryptoServiceStub(final AccountID nodeAccountID) {
		final ManagedChannel channel = buildManagedChannel(nodeAccountID);
		return Pair.of(CryptoServiceGrpc.newBlockingStub(channel), channel);
	}

	static Pair<FileServiceGrpc.FileServiceBlockingStub, ManagedChannel> getFileServiceStub(final AccountID nodeAccountID) {
		final ManagedChannel channel = buildManagedChannel(nodeAccountID);
		return Pair.of(FileServiceGrpc.newBlockingStub(channel), channel);
	}

	static Pair<SmartContractServiceGrpc.SmartContractServiceBlockingStub, ManagedChannel> getContractServiceStub(final AccountID nodeAccountID) {
		final ManagedChannel channel = buildManagedChannel(nodeAccountID);
		return Pair.of(SmartContractServiceGrpc.newBlockingStub(channel), channel);
	}

}
