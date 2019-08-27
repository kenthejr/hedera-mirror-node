
package com.hedera.parser;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.base.Stopwatch;
import com.google.protobuf.TextFormat;
import com.hedera.configLoader.ConfigLoader;
import com.hedera.configLoader.ConfigLoader.OPERATION_TYPE;
import com.hedera.databaseUtilities.ApplicationStatus;
import com.hedera.recordFileLogger.RecordFileLogger;
import com.hedera.recordFileLogger.RecordFileLogger.INIT_RESULT;
import com.hedera.utilities.Utility;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Hex;

/**
 * This is a utility file to read back service record file generated by Hedera node
 */
@Log4j2
public class RecordFileParser {

	static final int RECORD_FORMAT_VERSION = 2;
	static final byte TYPE_PREV_HASH = 1;       // next 48 bytes are hash384 or previous files
	static final byte TYPE_RECORD = 2;          // next data type is transaction and its record
	static final byte TYPE_SIGNATURE = 3;       // the file content signature, should not be hashed

	private static String thisFileHash = "";
	private static ApplicationStatus applicationStatus;

	public RecordFileParser() throws Exception {
		applicationStatus = new ApplicationStatus();
	}
	/**
	 * Given a service record name, read and parse and return as a list of service record pair
	 *
	 * @param fileName
	 * 		the name of record file to read
	 * @return return previous file hash
	 * @throws Exception 
	 */
	static public boolean loadRecordFile(String fileName, String previousFileHash) throws Exception {

		File file = new File(fileName);
		String newFileHash = "";
		
		if (file.exists() == false) {
			log.warn("File does not exist {}", fileName);
			return false;
		}
		long counter = 0;
		byte[] readFileHash = new byte[48];
		INIT_RESULT initFileResult = RecordFileLogger.initFile(fileName);
		Stopwatch stopwatch = Stopwatch.createStarted();

		if ((initFileResult == INIT_RESULT.OK) || (initFileResult == INIT_RESULT.SKIP)) {
			try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
				MessageDigest md = MessageDigest.getInstance("SHA-384");
				MessageDigest mdForContent = MessageDigest.getInstance("SHA-384");

				int record_format_version = dis.readInt();
				int version = dis.readInt();

				md.update(Utility.integerToBytes(record_format_version));
				md.update(Utility.integerToBytes(version));

				log.info("Loading version {} record file: {}", record_format_version, file.getName());

				while (dis.available() != 0) {

					try {
						byte typeDelimiter = dis.readByte();

						switch (typeDelimiter) {
							case TYPE_PREV_HASH:
								md.update(typeDelimiter);
								dis.read(readFileHash);
								md.update(readFileHash);

								if (Utility.hashIsEmpty(previousFileHash)) {
									log.error("Previous file hash not available");
									previousFileHash = Hex.encodeHexString(readFileHash);
								} else {
									log.trace("Previous file Hash = {}", previousFileHash);
								}
								newFileHash = Hex.encodeHexString(readFileHash);
								log.trace("New file Hash = {}", newFileHash);

								if (!newFileHash.contentEquals(previousFileHash)) {

									if (applicationStatus.getBypassRecordHashMismatchUntilAfter().compareTo(Utility.getFileName(fileName)) < 0) {
										// last file for which mismatch is allowed is in the past
										log.error("Hash mismatch for file {}. Previous = {}, Current = {}", fileName, previousFileHash, newFileHash);
										return false;
									}
								}
								break;
							case TYPE_RECORD:
								counter++;

								int byteLength = dis.readInt();
								byte[] rawBytes = new byte[byteLength];
								dis.readFully(rawBytes);
								if (record_format_version >= RECORD_FORMAT_VERSION) {
									mdForContent.update(typeDelimiter);
									mdForContent.update(Utility.integerToBytes(byteLength));
									mdForContent.update(rawBytes);

								} else {
									md.update(typeDelimiter);
									md.update(Utility.integerToBytes(byteLength));
									md.update(rawBytes);
								}
								Transaction transaction = Transaction.parseFrom(rawBytes);

								byteLength = dis.readInt();
								rawBytes = new byte[byteLength];
								dis.readFully(rawBytes);

								if (record_format_version >= RECORD_FORMAT_VERSION) {
									mdForContent.update(Utility.integerToBytes(byteLength));
									mdForContent.update(rawBytes);

								} else {
									md.update(Utility.integerToBytes(byteLength));
									md.update(rawBytes);
								}

								TransactionRecord txRecord = TransactionRecord.parseFrom(rawBytes);

								if (initFileResult != INIT_RESULT.SKIP) {
									boolean bStored = RecordFileLogger.storeRecord(counter, Utility.convertToInstant(txRecord.getConsensusTimestamp()), transaction, txRecord);
									if (bStored) {
										if (log.isTraceEnabled()) {
											log.trace("Transaction = {}, Record = {}", Utility.printTransaction(transaction), TextFormat.shortDebugString(txRecord));
										} else {
											log.debug("Stored transaction with consensus timestamp {}", txRecord.getConsensusTimestamp());
										}
									} else {
										RecordFileLogger.rollback();
										return false;
									}
								}
								break;
							case TYPE_SIGNATURE:
								int sigLength = dis.readInt();
								byte[] sigBytes = new byte[sigLength];
								dis.readFully(sigBytes);
								log.trace("File {} has signature {}", fileName, Hex.encodeHexString(sigBytes));
								if (RecordFileLogger.storeSignature(Hex.encodeHexString(sigBytes))) {
									break;
								}

							default:
								log.error("Unknown record file delimiter {} for file {}", typeDelimiter, file);
						}


					} catch (Exception e) {
						log.error("Exception {}", e);
						RecordFileLogger.rollback();
						return false;
					}
				}

				if (record_format_version >= RECORD_FORMAT_VERSION) {
					md.update(mdForContent.digest());
				}
				byte[] fileHash = md.digest();
				thisFileHash = Utility.bytesToHex(fileHash);

				log.trace("Calculated file hash for the current file {}", thisFileHash);

				RecordFileLogger.completeFile(thisFileHash, previousFileHash);
			} catch (Exception e) {
				log.error("Error parsing record file {} after {}", file, stopwatch, e);
				return false;
			}

			log.info("Finished parsing {} transactions from record file {} in {}", counter, file.getName(), stopwatch);
			applicationStatus.updateLastProcessedRcdHash(thisFileHash);
			return true;
		} else if (initFileResult == INIT_RESULT.SKIP) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * read and parse a list of record files
	 * @throws Exception 
	 */
	static public void loadRecordFiles(List<String> fileNames) throws Exception {
		String prevFileHash = applicationStatus.getLastProcessedRcdHash();

		Collections.sort(fileNames);
		
		for (String name : fileNames) {
			if (Utility.checkStopFile()) {
				log.info("Stop file found, stopping");
				return;
			}

			if (loadRecordFile(name, prevFileHash)) {
				prevFileHash = thisFileHash;
				Utility.moveFileToParsedDir(name, "/parsedRecordFiles/");
			} else {
				return;
			}
		}
	}

	public static void parseNewFiles(String pathName) throws Exception {
		log.debug( "Parsing record files from {}", pathName);
		if (RecordFileLogger.start()) {

			File file = new File(pathName);
			if ( ! file.exists()) {
				file.mkdirs();
			}

			if (file.isDirectory()) { //if it's a directory

				String[] files = file.list(); // get all files under the directory
				Arrays.sort(files);           // sorted by name (timestamp)

				// add directory prefix to get full path
				List<String> fullPaths = Arrays.asList(files).stream()
						.filter(f -> Utility.isRecordFile(f))
						.map(s -> file + "/" + s)
						.collect(Collectors.toList());

				if (fullPaths != null && fullPaths.size()!= 0) {
					log.trace("Processing record files: {}", fullPaths);
					loadRecordFiles(fullPaths);
				} else {
					log.debug("No files to parse");
				}
			} else {
				log.error("Input parameter {} is not a folder", pathName);
			}
			RecordFileLogger.finish();
		}
	}

	public static void main(String[] args) throws Exception {
		String pathName;
		applicationStatus = new ApplicationStatus();

		while (true) {
			if (Utility.checkStopFile()) {
				log.info("Stop file found, exiting");
				System.exit(0);
			}

			pathName = ConfigLoader.getDefaultParseDir(OPERATION_TYPE.RECORDS);

			if (pathName != null) {
				parseNewFiles(pathName);
			}
		}
	}

	/**
	 * Given a service record name, read its prevFileHash
	 *
	 * @param fileName
	 * 		the name of record file to read
	 * @return return previous file hash's Hex String
	 */
	static public String readPrevFileHash(String fileName) {
		File file = new File(fileName);
		if (file.exists() == false) {
			log.warn("File does not exist {}", fileName);
			return null;
		}
		byte[] prevFileHash = new byte[48];
		try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
			// record_format_version
			dis.readInt();
			// version
			dis.readInt();

			byte typeDelimiter = dis.readByte();

			if (typeDelimiter == TYPE_PREV_HASH) {
				dis.read(prevFileHash);
				String hexString = Hex.encodeHexString(prevFileHash);
				log.trace("Read previous file hash {} for file {}", hexString, fileName);
				return hexString;
			} else {
				log.error("Expecting previous file hash, but found file delimiter {} for file {}", typeDelimiter, fileName);
			}
		} catch (Exception e) {
			log.error("Error reading previous file hash {}", fileName, e);
		}

		return null;
	}
}
