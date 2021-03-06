[![CircleCI](https://circleci.com/gh/hashgraph/hedera-mirror-node/tree/master.svg?style=shield&circle-token=710f183adf8aa2890272404e0c53e10898b94882)](https://circleci.com/gh/hashgraph/hedera-mirror-node/tree/master)

# Beta Mirror Node

This BetaMirrorNode implementation supports CryptoService, FileService and SmartContractService through a proxy.
It can also parse the RecordStream, balance, and events files generated by Hedera mainnet and testnet nodes.

## Overview

Hedera mirror nodes receive the information from the mainnet nodes and they provide value-added services such as providing audit support, access to historical data, transaction analytics, visibility services, security threat modeling, state proofs, data monetization services, etc. Mirror nodes can also run additional business logic to support applications built using Hedera mainnet.

While mirror nodes receive information from the mainnet nodes, they do not contribute to consensus on the mainnet, and their votes are not counted. Only the votes from the mainnet nodes are counted for determining consensus. The trust of Hedera mainnet is derived based on the the consensus reached by the mainnet nodes. That trust is transferred to the mirror nodes using signatures, chain of hashes and state proofs.

### Beta mirror node

Eventually, the mirror nodes can run the same code as the Hedera mainnet nodes so that they can see the transactions in real time. To make the initial deployments easier, the beta mirror node strives to take away the burden of running a full Hedera node through creation of periodic files that contain processed information (such as account balances or transaction records), and have the full trust of the Hedera mainnet nodes. The beta mirror node software reduces the processing burden by receiving pre-constructed files from the mainnet, validating those, populating a database and providing REST APIs.


#### Advantages of beta mirror node

- Lower compute, bandwidth requirement
- It allows users to only save what they care about, and discard what they don’t (lower storage requirement)
- Easy searchable database so the users can add value quickly
- Easy to consume REST APIs to make integrations faster

## Description

The Beta mirror node works as follows:

- When a transaction reaches consensus, Hedera nodes add the transaction and its associated record to a record file.
- The file is closed on a regular cadence and a new file is created for the next batch of transactions and records. The interval is currently set to 5 seconds but may vary between networks.
- Once the file is closed, nodes generate a signature file which contains the signature generated by the node for the record file.
- Record files also contain the hash of the previous record file, thus creating an unbreakable validation chain.

- The signature and record files are then uploaded from the nodes to Amazon S3 and Google File Storage.

- This mirror node software downloads signature files from either S3 or Google File Storage.
- The signature files are validated to ensure more than 2/3 of the nodes in the address book (stored in a `0.0.102` file) have the same signature.
- For each valid signature file, the corresponding record file is then downloaded from the cloud.
- Record files can then be processed and transactions and records processed for long term storage.

- Event files are handled in exactly the same manner.

- In addition, nodes regularly generate a balance file which contains the list of Hedera accounts and their corresponding balance which is also uploaded to S3 and Google File Storage.
- The files are also signed by the nodes.
- This mirror node software can download the balance files, validate 2/3rd of nodes have signed and then process the balance files for long term storage.

----

## Quickstart

### Requirements

- [ ] Docker
- [ ] Docker-compose
- [ ] Address book update information:
  - [ ] Node ID - your node of choice (e.g. 0.0.3)
  - [ ] Node Address - IP address and port number of your node of choice (e.g. 35.232.131.251:50211)
  - [ ] Operator ID - Your Hedera Account ID
  - [ ] Operator Secret Key - The secret key that can sign transactions on behalf of your Operator ID.

```
git clone git@github.com:hashgraph/hedera-mirror-node.git
cd hedera-mirror-node
cp config/config.json.sample config/config.json
nano config/config.json // Insert AWS S3 credentials. 
cp docker/dotenv.sample docker/.env
./buildimages.sh

  // You'll now be asked a few questions to finalize automated mirror node configuration.

  Compile source via 1-docker-compose, 2-local maven, 3-skip?
  1) Docker
  2) Local
  3) Skip
  #? 1

  Would you like to fetch or use an existing address book file (0.0.102) (enter 1, 2, 3 or 4)?
  1) Yes			3) Integration-Testnet
  2) Skip			4) Public-Testnet

  Choose 
  (3) to copy the address book file we provide or 
  (1) to generate it from the network itself
    
    #? 1

    Input node address (x.x.x.x:port)
    {{Node Address}}
    Input node ID (0.0.x)
    {{Node ID}}
    Input operator ID (0.0.x)
    {{Operator ID}}
    Input operator key (302...)
    {{Operator Secret Key}}
```

Follow instructions above for setting up the `config.json` file and the `.env` file in the `docker` folder to ensure environment variables are set correctly.

Note: It is recommended that for a quickstart, only the AWS keys are input into the `config.json` file and all settings are left as they are.

example `.env` file.

```text
POSTGRES_DB=postgres
POSTGRES_USER=postgres
POSTGRES_PASSWORD=mysecretpassword
POSTGRES_PORT=5432
PGDATA=/var/lib/postgresql/data/pgdata
# This user is used by the REST API to gain read only access to the necessary database tables
DB_USER=api
DB_PASS=apipass
DB_NAME=postgres
# This is the port the REST API will listen onto
PORT=5551
```

Containers use persisted volumes as follows:

- `./MirrorNodePostgresData` on your local machine maps to `/var/lib/postgresql/data` in the containers. This contains the files for the PostgreSQL database.
Note: If you database container fails to initialise properly and the database fails to run, you will have to delete this folder prior to attempting a restart otherwise the database initialisation scripts will not be run.

- `./runtime` on your local machine maps to `/MirrorNodeCode` in the containers. This contains the runtime and configuration files for loading and parsing files.
- `./MirrorNodeData` on your local machine maps to `/MirrorNodeData` in the containers. This contains files downloaded from S3 or GCP.

These are necessary not only for the database data to be persisted, but also so that the parsing containers can access file obtained via the downloading containers

Docker compose scripts are available in the `docker` folder.

A `buildImages.sh` script ensures the necessary data is available to the images via volumes, builds the images and starts the containers.

`buildimages.sh` will first prompt whether youd like to compile sources either using a docker container, your local maven installation or skip the compilation, then prompt whether you want to download the 0.0.102 file from the network (it is recommended you do so the first time).
If you answer 2 (no), the file will not be downloaded, if you answer 1 (yes), you will be prompted for the following information:

-Node address in the format of `ip:port` or `host:port`. (e.g. 192.168.0.2:50211)
-Node ID, the Hedera account for the node (e.g. 0.0.3).
-Operator ID, your account (e.g. 0.0.2031)
-Operator key, the private key for your account

Note: Shutting down the database container via `docker-compose down` may result in a corrupted database that may not restart or may take longer than usual to restart.

In order to avoid this, shell into the container and issue the following command:

Use `docker ps` to get the name of the database container, it should be something like `mirror-node-postgres`.

Use the command `docker exec -it docker_mirror-node-postgres_1 /bin/sh` to get a shell in the container.

`su - postgres -c "PGDATA=$PGDATA /usr/local/bin/pg_ctl -w stop"`

You may now power down the docker image itself.

----

## Prerequisites

This mirror node beta requires Java version 10 or above.
If you are planning on using the docker compose images, you'll need `Docker` installed.
Without `Docker`, you will need to install `PostgreSQL` versions 10 or 11.

## Compile from source code

Run `./mvnw install -DskipTests` from the `MirrorNode` directory.

This will compile a runnable mirror node jar file in the `target` directory and copy `config.json.sample` into the same directory.

`cd target`

## Setup your environment

The build process has copied sample files to the `target/config` or the `/runtime/config` folder depending on whether you are running locally or via `docker-compose`.

- `config.json.sample` - rename this file to `config.json` and edit so that the configuration parameters that are appropriate to your environment are setup. See section below on configuration file specifics.

- the file prefixed with '0.0.102' is the contents of a file hosted on Hedera with file ID `0.0.102`. This file contains the address book from the Hedera network which lists nodes and their public keys for signature verification purposes. Ensure the appropriate one for your network is identified in the `config.json` file (addressBookFile entry) otherwise signature verification will fail.

Pay close attention to the contents of these configuration files, they directly affect how the mirror node operates.

### 0.0.102 file

The `0.0.102` file contains the address book, that is the list of nodes, their account number and public key(s). This file is different on every network so it is imperative to ensure you have the correct one for each network, else the signature verification process will fail.

#### Creating or updating the address book file (0.0.102 file)

Set the following environment variables or add them to a `.env` file.

```text
NODE_ADDRESS=127.0.0.1:50211
NODE_ID=0.0.x
OPERATOR_ID=0.0.x
OPERATOR_KEY=your account's private key
```

`NODE_ADDRESS` is the IP address/url + port of the node you wish to request the file from.
`NODE_ID` is the account number of the node (0.0.x).
`OPERATOR_ID` is your own account number on the network (0.0.x).
`OPERATOR_KEY` is your private key for the above account.

Run the following command to update the address book at the location specified in `config.json`.

```shell
java -cp mirrorNode.jar com.hedera.addressBook.NetworkAddressBook
```

If no errors are output, the file specified by the `addressBookFile` parameter of the `config.json` file will now contain the network's address book.

Once setup, the file will be automatically updated as the mirror node software parses fileUpdate transactions that pertain to this file.

### config.json

Note: Changes to this file while downloading or processing is taking place may be overwritten by the software. Make sure all processes are stopped before making changes.

| Parameter name  | Default value  | Description  |
|---|---|---|
| cloud-provider | `"S3"` | Either `S3` or `GCP` depending on where you want to download files from |
| clientRegion | `"us-east-2"` | The region which you want to download from |
| bucketName | `"hedera-export"` | The name of the bucket containing the files to download |
| accessKey | `""` | Your S3 or GCP access key |
| secretKey | `""` | Your S3 or GCP secret key |
| downloadToDir | `"/MirrorNodeData"` | The location where downloaded files will reside |
| proxyPort | `50777` | The port the mirror node proxy will listen onto |
| addressBookFile | `"./config/0.0.102"` | The location of the address book file file |
| accountBalancesS3Location | `"accountBalances/balance"` | The location of the account balances files in the cloud bucket |
| recordFilesS3Location | `"recordstreams/record"` | The location of the record files in the cloud bucket |
| dbName | `"postgres"` | The name of the database |
| dbUrl | `"jdbc:postgresql://localhost:5433/postgres"` | The connection string to access the database |
| dbUsername | `"postgres"` | The username to access the database |
| dbPassword | `"mysecretpassword"` | The password to access the database |
| apiUsername | `"api"` | The database user for the REST API |
| apiPassword | `"mysecretpassword"` | The password for the REST API user |
| maxDownloadItems | `0` | The maximum number of new files to download at a time, set to `0` in production, change to `10` or other low number for testing or catching up with a large number of files. |
| persistClaims | `false` | Determines whether claim data is persisted to the database or not |
| persistFiles | `"ALL"` | Determines whether file data is persisted to the database or not, can be set to `ALL`, `NONE` or `SYSTEM`. `SYSTEM` means only files with a file number lower than `1000` will be persisted |
| persistContracts | `true` | Determines whether contract data is persisted to the database or not |
| persistCryptoTransferAmounts | `true` | Determines whether crypto transfer amount data is persisted to the database or not |

The following environment variables may be used instead of values in the `config.json` file for additional security.
Environment variables if set will take precedence over values in the `config.json` file.
Environment variables may be set through the command line `export varname=value` or via a `.env` file located in the folder where the java classes are executed from

Note: this requires additional information to be stored in the `config.json`, `.env` or environment variables as follows:

| json parameter name | corresponding environment variable |
|---------------------|------------------------------------|
| dbUsername | HEDERA_MIRROR_DB_USER |
| dbPassword | HEDERA_MIRROR_DB_PASS |
| accessKey | HEDERA_S3_ACCESS_KEY |
| secretKey | HEDERA_S3_SECRET_KEY |
| dbName | HEDERA_MIRROR_DB_NAME |
| apiUsername | DB_USER |
| apiPassword | DB_PASS |

Sample `./.env` file.

```text
HEDERA_S3_ACCESS_KEY=accessKey
HEDERA_S3_SECRET_KEY=secretKey
```
## Installing the database

You can skip this step if you're using Docker containers.

Ensure you have a postgreSQL server running (versions 10 and 11 have been tested) with the mirror node software.

Flyway (https://flywaydb.org/getstarted/) is used to manage the database schema.

All database scripts reside in `src/main/resources/postgres`.

`postgresInit.sql` should be used to initialise the database and owner. Please edit the file with usernames, passwords, etc... you wish to use.

Then flyway should be used to biuld the initial set of tables, and apply any changes. Those are determined by files names `Vx.x__`.
Note: The `Vx.x` scripts use variables which you should set prior to running the scripts.
Example: `\set db_name='mydatabasename'`

Make sure the `config/config.json` or `.env` file have values that match the above.

Check the output of the script carefully to ensure no errors occurred.

## Upgrading the database

Upgrades are performed by running the `migrate` command of the Flyway utility.

## Running the various mirror node components

You can skip this section if you're running docker containers.

### Note about error when running the software

The error below may appear on the console when running the `.jar` file, this is normal and nothing to be concerned about.

```code
WARNING: An illegal reflective access operation has occurred
WARNING: Illegal reflective access by com.google.protobuf.UnsafeUtil (file:/home/greg/mirrornode/lib/protobuf-java-3.5.1.jar) to field java.nio.Buffer.address
WARNING: Please consider reporting this to the maintainers of com.google.protobuf.UnsafeUtil
WARNING: Use --illegal-access=warn to enable warnings of further illegal reflective access operations
WARNING: All illegal access operations will be denied in a future release
```

### To Run BetaMirrorNode Proxy

*Note: The beta mirror node proxy doesn't select nodes by itself, rather it inspects the transactions it receives, extracts the target node information from the transaction (`NodeAccountID` from `transactionBody`) and forwards the transaction to this node.*

Run the following command:

```shell
java -jar mirrorNode.jar
```

### To Download RecordStream file(s)

Run the following command:

```shell
java -cp mirrorNode.jar com.hedera.downloader.RecordFileDownloader
```

Record files and signature files will be downloaded from S3 to the location specified in the `config.json` file.

### To Download Balance file(s)

Run the following command:

```shell
java -cp mirrorNode.jar com.hedera.downloader.AccountBalancesDownloader
```

Balance files will be downloaded from S3 to the location specified in the `config.json` file.

Example file

```text
year,month,day,hour,minute,second
2019,JUNE,28,17,29,17
shard,realm,number,balance
0,0,1,0
0,0,2,4530999689861900540
... continues
```

### To Parse RecordStream file(s)

Run the following command:

```shell
java -cp mirrorNode.jar com.hedera.parser.RecordFileParser
```

### To Parse Balance file(s)

This project provides the ability to log balances for all accounts, including history of balance changes

Run the following command:

```shell
java -cp mirrorNode.jar com.hedera.balanceFileLogger.BalanceFileLogger
```

### To download and parse record files in one command

```shell
java -cp mirrorNode.jar com.hedera.downloader.DownloadAndParseRecordFiles
```

### To Send Transactions or Queries to the BetaMirrorNode Proxy

Using a client which is able to generate and send transactions to a Hedera node, update the configuration of the client application such that it sends its transactions to the proxy host and port instead of a Hedera node.

## Docker compose

Docker compose scripts are provided and run all the mirror node components:

- PostgreSQL database
- Balance files downloader
- Balance files processor
- Record files downloader and parser
- 102 file updater
- REST API

## REST API

A REST API to the database is available under `rest-api`.

To start it, `cd rest-api` then `npm install`.

Create a `.env` file as per below and run with `npm start`.

You can also unittest using jest by using `npm test`.

example `.env` file:

```TEXT
DB_USER=api
DB_PASS=apipass
DB_NAME=postgres
# This is the port the REST API will listen onto
PORT=5551
# server hosting the database
DB_HOST=localhost
```

`PORT` is the port number the REST API will listen onto.

## Application status data

The mirror node saves its current state to the database in a table called `t_application_status`.
While the values in this table are updated in real time, any changes you wish to make here to promote to the application require that you first stop the application, make the changes in the database, then restart the application

| Status name  | Description  |
|---|---|---|
| Event hash mismatch bypass until after | If a hash mismatch occurs before this event file name, processing into the database will stop. Leave blank to catch all mismatches, set to `X` to bypass all or set to the filename before which hash mismatches are ok and understood |
| Record hash mismatch bypass until after | If a hash mismatch occurs before this record name, processing into the database will stop. Leave blank to catch all mismatches, set to `X` to bypass all or set to the filename before which hash mismatches are ok and understood |
| Last processed record hash | The hash of the last record file processed into the database |
| Last processed event hash | The hash of the last event file processed into the database |
| Last valid downloaded record file name | The name of the last record file to have passed signature verification |
| Last valid downloaded record file hash | The hash of the last record file to have passed signature verification |
| Last valid downloaded event file name | The name of the last event file to have passed signature verification |
| Last valid downloaded event file hash | The hash of the last event file to have passed signature verification |
| Last valid downloaded balance file name | The name of the last balance file to have passed signature verification |

## Troubleshooting

### Checking for errors

Check for errors in the `output/hedera-mirror-node.log` file. Log files are rotated every 100Mb by default, archived log files can be found in the 'logs' folder.

### Record files

* Recordstream files that have successfully been validated against signatures will be placed in the `"downloadToDir"/recordstreams/valid` directory.
If there are no files in this folder, it's possible that either you `0.0.102` file is incorrect for this network, or signature files are still being downloaded.

* Recordstream files that have successfully been parsed will be moved to `"downloadToDir"/recordstreams/parsedRecordFiles'

* If your `maxDownloadItems` is set to 0 in the `config.json` file, the docker image downloads all new signature files from all nodes before starting processing into the database. If there are many files to catch up, it may be a long while before processing of these files takes place.
You may try to set the `maxDownloadItems` to a number such as 10 or 20 to download and process new files in batches.

* The above also applies if you are running the `downloader.DownloadAndParseRecordFiles` java class standalone.

## Contributing

Refer to [CONTRIBUTING.md](CONTRIBUTING.md)

## License

Apache License 2.0, see [LICENSE](LICENSE).

