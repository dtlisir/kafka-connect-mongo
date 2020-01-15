package com.teambition.kafka.connect.mongo.source

import com.teambition.kafka.connect.mongo.database.MongoClientLoader
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.ANALYZE_SCHEMA_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.BATCH_SIZE_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.DATABASES_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.INITIAL_IMPORT_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.MONGO_URI_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.SCHEMA_NAME_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.SCHEMA_REGISTRY_URL_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.TOPIC_PREFIX_CONFIG
import com.teambition.kafka.connect.mongo.source.MongoSourceConfig.Companion.USE_CHANGE_STREAMS
import io.confluent.kafka.schemaregistry.client.rest.RestService
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.data.SchemaBuilder
import org.apache.kafka.connect.data.Struct
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * @author Xu Jingxin
 */
abstract class AbstractMongoSourceTask : SourceTask() {
    abstract val log: Logger
    // Configs
    protected var uri = ""
    private var schemaName = ""
    private var batchSize = 100
    protected var initialImport = false
    private var topicPrefix = ""
    // Database and collection joined with dot [mydb.a,mydb.b]
    protected var databases = listOf<String>()
    private var schemas = mutableMapOf<String, Schema>()
    // Message queue
    protected val messages = ConcurrentLinkedQueue<Message>()
    // Runtime states
    // Sleep time will get double of it's self when there was no records return in the poll function
    // But will not larger than maxSleepTime
    private var sleepTime = 50L
    private var maxSleepTime = 10000L
    private var analyzeSchema = false
    protected var useChangeStreams = false

    override fun version(): String = MongoSourceConnector().version()
    protected var unrecoverable: Throwable? = null

    /**
     * Parse the config properties into in-use type and format
     * @param props
     */
    override fun start(props: Map<String, String>) {
        log.trace("Parsing configuration: {}", props)
        batchSize = Integer.parseInt(props[BATCH_SIZE_CONFIG])
        initialImport = props[INITIAL_IMPORT_CONFIG]?.toLowerCase()?.equals("true") ?: false
        schemaName = props[SCHEMA_NAME_CONFIG] ?: throw Exception("Invalid config $SCHEMA_NAME_CONFIG")
        topicPrefix = props[TOPIC_PREFIX_CONFIG] ?: throw Exception("Invalid config $TOPIC_PREFIX_CONFIG")
        uri = props[MONGO_URI_CONFIG] ?: throw Exception("Invalid config $MONGO_URI_CONFIG")
        databases = props.getValue(DATABASES_CONFIG).split(",").map(String::trim).dropLastWhile(String::isEmpty)
        analyzeSchema = (props[ANALYZE_SCHEMA_CONFIG] == "true")
        useChangeStreams = (props[USE_CHANGE_STREAMS] == "true")
        MongoClientLoader.getClient(uri)
        if (analyzeSchema) {
            val schemaRegistryUrl = props[SCHEMA_REGISTRY_URL_CONFIG]
                ?: throw Exception("Invalid config $SCHEMA_REGISTRY_URL_CONFIG")
            val schemaRegistryClient = RestService(schemaRegistryUrl)
            log.info("Init avro schemas")
            databases.map { it.replace(".", "_") }
                .forEach {
                    try {
                        val restSchema = schemaRegistryClient.getLatestVersion("${topicPrefix}_$it-value")
                        val schema = SchemaParser.parse(restSchema)
                        CachedSchema.set(schema)
                    } catch (e: Exception) {
                        log.warn("Init schema for $it error, schema not exist or contains invalid structure!")
                        e.printStackTrace()
                    }
                }
        } else {
            log.info("Init json schema")
            databases.map { it.replace(".", "_") }
                .forEach {
                    schemas[it] = SchemaBuilder.struct().name(schemaName + "_" + it)
                        .field("ts", Schema.OPTIONAL_INT32_SCHEMA)
                        .field("inc", Schema.OPTIONAL_INT32_SCHEMA)
                        .field("id", Schema.OPTIONAL_STRING_SCHEMA)
                        .field("database", Schema.OPTIONAL_STRING_SCHEMA)
                        .field("op", Schema.OPTIONAL_STRING_SCHEMA)
                        .field("object", Schema.OPTIONAL_STRING_SCHEMA).build()
                }
        }
    }

    override fun poll(): List<SourceRecord> {
        unrecoverable?.let { throw it }
        val records = mutableListOf<SourceRecord>()
        while (!messages.isEmpty() && records.size < batchSize) {
            val message = messages.poll()
            try {
                val id = (message.oplog["o"] as Document)
                    .let { it["_id"] as ObjectId }
                    .toString()
                val struct = getStruct(message.oplog)
                records.add(
                    SourceRecord(
                        getPartition(StructUtil.getDB(message.oplog)),
                        message.offset.toOffset(),
                        StructUtil.getTopic(message.oplog, topicPrefix),
                        Schema.OPTIONAL_STRING_SCHEMA,
                        id,
                        struct.schema(),
                        struct
                    )
                )
            } catch (e: Exception) {
                log.error(e.message)
            }
            log.trace(message.toString())
        }
        if (records.size == 0) {
            sleepTime = min(sleepTime * 2, maxSleepTime)
            Thread.sleep(sleepTime)
        } else {
            sleepTime = 50L
        }
        return records
    }

    override fun stop() {}

    /**
     * @param db database with collection like 'mydb.test'
     */
    protected fun getPartition(db: String): Map<String, String> {
        return Collections.singletonMap("mongo", db)
    }

    /**
     * Build struct from document, whether in basic schema or analyzed schema
     * @param oplog Document formatted as oplog
     * @return Struct
     */
    private fun getStruct(oplog: Document): Struct {
        return if (analyzeSchema) {
            SchemaMapper.getAnalyzedStruct(oplog, schemaName)
        } else {
            getBasicStruct(oplog)
        }
    }

    private fun getBasicStruct(oplog: Document): Struct {
        val db = StructUtil.getDB(oplog).replace(".", "_")
        val schema = schemas[db] ?: throw Exception("Can not find the schema of database $db")
        val struct = Struct(schema)
        val bsonTimestamp = oplog["ts"] as BsonTimestamp
        val body = oplog["o"] as Document
        val id = (body["_id"] as ObjectId).toString()
        struct.put("ts", bsonTimestamp.time)
        struct.put("inc", bsonTimestamp.inc)
        struct.put("id", id)
        struct.put("database", db)
        struct.put("op", oplog["op"])
        if (oplog["op"].toString() == "d") {
            struct.put("object", null)
        } else {
            struct.put("object", (oplog["o"] as Document).toJson())
        }
        return struct
    }
}
