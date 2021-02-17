package bindiego;

import bindiego.io.elastic.ConnectionConf;
import bindiego.io.elastic.RetryConf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.sql.*;

// Import SLF4J packages.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TimePartitioning;

import com.google.cloud.bigtable.beam.CloudBigtableIO;
import com.google.cloud.bigtable.beam.CloudBigtableTableConfiguration;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.InsertRetryPolicy;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.FileBasedSink;
import org.apache.beam.sdk.io.jdbc.JdbcIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider.NestedValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.Window.ClosingBehavior;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.codehaus.jackson.map.ObjectMapper;

import bindiego.io.elastic.WindowedFilenamePolicy;
import bindiego.utils.DurationUtils;
import bindiego.utils.SchemaParser;
import bindiego.io.elastic.ElasticsearchIO;

public class BindiegoStreaming {
    /* extract the csv payload from message */
    public static class ExtractPayload extends DoFn<PubsubMessage, String> {

        public ExtractPayload(final PCollectionView<Map<String, String>> lookupTable) {
            this.lookupTable = lookupTable;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx, MultiOutputReceiver r) 
                throws IllegalArgumentException {

            // StringBuilder sb = new StringBuilder(256);
            StringBuilder sb = new StringBuilder();
            String payload = null;

            Map<String, String> dimTable = ctx.sideInput(lookupTable);

            // TODO: data validation here to prevent later various outputs inconsistency
            try {
                PubsubMessage psmsg = ctx.element();
                // CSV format
                // raw:   "event_ts,thread_id,thread_name,seq,dim1,metrics1"
                // after: "event_ts,thread_id,thread_name,seq,dim1,metrics1,process_ts,dim1_val"
                payload = new String(psmsg.getPayload(), StandardCharsets.UTF_8);
                sb.append(payload)
                    .append(',') // hardcoded csv delimiter
                    .append(Long.valueOf(System.currentTimeMillis()).toString()); // append process timestamp

                // dimemsion table lookup to complement dim value
                String dimVal = dimTable.get(sb.toString().split(",")[4]);
                dimVal = dimVal == null ? "Not Found" : dimVal;

                sb.append(',')
                    .append(dimVal);

                logger.debug("Extracted raw message: " + sb.toString());

                r.get(STR_OUT).output(sb.toString());

                // use this only if the element doesn't have an event timestamp attached to it
                // e.g. extract 'extractedTs' from psmsg.split(",")[0] from a CSV payload
                // r.get(STR_OUT).outputWithTimestamp(str, extractedTs);
            } catch (Exception ex) {
                if (null == payload)
                    payload = "Failed to extract pubsub payload";

                r.get(STR_FAILURE_OUT).output(payload);

                logger.error("Failed extract pubsub message", ex);
            }
        }

        private final PCollectionView<Map<String, String>> lookupTable;
    }

    /**
     * Append window information to the end of csv string/row
     * <...>, window, pane_info, pane_idx, pane_nonspeculative_idx, 
     *        is_first, is_last, pane_timing, pane_event_ts
     *
     * FIXME: you may want to handle errors here
     */
    public static class AppendWindowInfo extends DoFn<String, String> {
        @ProcessElement
        public void processElement(ProcessContext ctx, IntervalWindow window)
                throws IllegalArgumentException {
            
            StringBuilder sb = new StringBuilder();

            try {
                sb.append(ctx.element())
                    .append(',').append(window.toString())
                    .append(',').append(ctx.pane().toString())
                    .append(',').append(ctx.pane().getIndex())
                    .append(',').append(ctx.pane().getNonSpeculativeIndex())
                    .append(',').append(ctx.pane().isFirst())
                    .append(',').append(ctx.pane().isLast())
                    .append(',').append(ctx.pane().getTiming().toString())
                    .append(',').append(ctx.timestamp().getMillis());

                ctx.output(sb.toString());
            } catch (Exception ex) {
                logger.error("Failed to append window information", ex);
            }
        }
    }

    /* add timestamp for PCollection<T> data
     *
     * this implementation suppose CSV and stampstamp as 1st column
     */
    private static class SetTimestamp implements SerializableFunction<String, Instant> {
        @Override
        public Instant apply(String input) {
            String[] components = input.split(","); // assume CSV
            try {
                return new Instant(Long.parseLong(components[0].trim())); // assume 1st column is timestamp
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                return Instant.now();
            }
        }
    }

    /* Convert Csv to Avro */
    public static class ConvertCsvToAvro extends DoFn<String, GenericRecord> {
        public ConvertCsvToAvro(String schemaJson, String delimiter) {
            this.schemaJson = schemaJson;
            this.delimiter = delimiter;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws IllegalArgumentException {
            String[] csvData = ctx.element().split(delimiter);
           
            Schema schema = new Schema.Parser().parse(schemaJson);

            // Create Avro Generic Record
            GenericRecord genericRecord = new GenericData.Record(schema);
            List<Schema.Field> fields = schema.getFields();

            for (int index = 0; index < fields.size(); ++index) {
                Schema.Field field = fields.get(index);
                String fieldType = field.schema().getType().getName().toLowerCase();

                // REVISIT: suprise, java can switch string :)
                switch (fieldType) {
                    case "string":
                        genericRecord.put(field.name(), csvData[index]);
                        break;
                    case "boolean":
                        genericRecord.put(field.name(), Boolean.valueOf(csvData[index]));
                        break;
                    case "int":
                        genericRecord.put(field.name(), Integer.valueOf(csvData[index]));
                        break;
                    case "long":
                        genericRecord.put(field.name(), Long.valueOf(csvData[index]));
                        break;
                    case "float":
                        genericRecord.put(field.name(), Float.valueOf(csvData[index]));
                        break;
                    case "double":
                        genericRecord.put(field.name(), Double.valueOf(csvData[index]));
                        break;
                    default:
                        throw new IllegalArgumentException("Field type " 
                            + fieldType + " is not supported.");
                }
            }

            ctx.output(genericRecord);
        }

        private String schemaJson;
        private String delimiter;
    }

    // Read JDBC lookup table
    // Why: Above service will return a PCollection, result the caller to produce a PCollection<PCollection<...>>
    //      when do p.apply(...)
    //
    // REVISIT: cheap & nasty only for demo purpose
    public static class BindiegoJdbcServiceExternal {
        public static Map<String, String> read(final String jdbcClass, final String jdbcConn,
                final String jdbcUsername, final String jdbcPassword) {
            Map<String, String> dict = new HashMap<>();
            Connection conn = null;

            try {
                Class.forName(jdbcClass);
                conn = DriverManager.getConnection(
                    jdbcConn, jdbcUsername, jdbcPassword);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("select dim1, dim1_val from t_dim1;");

                while(rs.next()) {
                    dict.put(rs.getString(1), rs.getString(2));
                }

                dict.forEach((k, v) ->
                    logger.debug("bindiego from mysql: key = " + k + " value = " + v));
            } catch (Exception ex) {
                logger.error(ex.getMessage(), ex);
            } finally {
                // if (conn != null)
                try {
                    conn.close();
                } catch (Exception ex) {
                    logger.warn(ex.getMessage(), ex);
                }
            }

            /*
            Instant now = Instant.now();
            org.joda.time.format.DateTimeFormatter dtf = 
                org.joda.time.format.DateTimeFormat.forPattern("HH:MM:SS");

            dict.put("bindiego_A", now.minus(Duration.standardSeconds(30)).toString(dtf));
            dict.put("bindiego_B", now.minus(Duration.standardSeconds(30)).toString());
            */

            return dict;
        }
    }

    /**
     * Produce KV from the process CSV data for later 'aggregation by key' operations
     *
     * @Input processed csv data
     * @Output <key, value> 
     *         key = dim1, dim1 extraced from the processed csv data
     *         value = process csv data
     */
    public static class ProduceKv extends DoFn<String, KV<String, String>> {
        public ProduceKv(String delimiter) {
            this.delimiter = delimiter;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws IllegalArgumentException {
            String[] csvData = ctx.element().split(delimiter);

            //REVISIT: handle potential errors
            
            ctx.output(KV.of(csvData[4], ctx.element()));
        }

        private String delimiter;
    }

    static void run(BindiegoStreamingOptions options) throws Exception {
        // FileSystems.setDefaultPipelineOptions(options);

        Pipeline p = Pipeline.create(options);

        // Create a side input as a lookup table in order to enrich the input data
        // Long is NOT infinite, but should be fine mostly :-)
        /* REVISIT: This does NOT work, when updated, consumer's corresponding window will
         * receive another view, so asSingleton() will not work. Multimap may double the RAM
         * usage which is not a viable solution IMO. I would recommend wait for retraction or
         * simply update the pipeline.
         */
        /*
        PCollectionView<Map<String, String>> lookupTable =
            p.apply("Trigger for updating the side input (lookup table)", 
                GenerateSequence.from(0).withRate(1, Duration.standardSeconds(20L)))// FIXME: hardcoded
            .apply(
                Window.<Long>into(new GlobalWindows())
                    .triggering(Repeatedly.forever(AfterProcessingTime.pastFirstElementInPane()))
                    .discardingFiredPanes())
                    // .accumulatingAndRetractingFiredPanes())
            .apply(
                ParDo.of(
                    new DoFn<Long, Map<String, String>>() {
                        @ProcessElement
                        public void process(
                            @Element Long input,
                            PipelineOptions po, 
                            OutputReceiver<Map<String, String>> o) {
                                BindiegoStreamingOptions op = po.as(BindiegoStreamingOptions.class);
                                o.output(BindiegoJdbcServiceExternal.read(
                                    op.getJdbcClass(),
                                    op.getJdbcConn(),
                                    op.getJdbcUsername(),
                                    op.getJdbcPassword()
                                ));
                        }
                    })
            )
            .apply(Latest.<Map<String, String>>globally())
            .apply(View.asSingleton());
        */

        /*
         * Debug code for JDBC data refresh
         */
        /*
        p.apply(GenerateSequence.from(0).withRate(1, Duration.standardSeconds(2L)))
            .apply(Window.into(FixedWindows.of(Duration.standardSeconds(10))))
            .apply(Min.longsGlobally().withoutDefaults())
            .apply(
                ParDo.of(
                    new DoFn<Long, KV<Long, Long>>() {
                        @ProcessElement
                        public void process(ProcessContext c) {
                            Map<String, String> kv = c.sideInput(lookupTable);
                            c.outputWithTimestamp(KV.of(1L, c.element()), Instant.now());

                            logger.debug("bindiego consumer - lookup table size: " + kv.size());
                            kv.forEach((k, v) ->
                                logger.debug("bindiego cnsumer - Key = " + k + ", Value = " + v));
                        }
                    }
                ).withSideInputs(lookupTable)
            );
        */

        // Read JDBC lookup table
        final PCollectionView<Map<String, String>> lookupTable = 
            p.apply("Read lookup table from JDBC data source", 
                    JdbcIO.<KV<String, String>>read()
                // .withDataSourceConfiguration(
                .withDataSourceProviderFn(JdbcIO.PoolableDataSourceProvider.of(
                    JdbcIO.DataSourceConfiguration.create(
                        options.getJdbcClass(), options.getJdbcConn())
                    .withUsername(options.getJdbcUsername())
                    .withPassword(options.getJdbcPassword())))
                .withQuery("select dim1, dim1_val from t_dim1;") // FIXME: hardcoded
                .withCoder(KvCoder.of(StringUtf8Coder.of(),
                    StringUtf8Coder.of())) // e.g. BigEndianIntegerCoder.of() for Integer
                .withRowMapper(new JdbcIO.RowMapper<KV<String, String>>() {
                    public KV<String, String> mapRow(ResultSet resultSet) throws Exception {
                        return KV.of(resultSet.getString(1), resultSet.getString(2)); // e.g. resultSet.getInt(1)
                    }
                })
            )
            .apply("Produce broadcast view for lookup", View.<String, String>asMap());

        /* Raw data processing */
        PCollection<PubsubMessage> messages = p.apply("Read Pubsub Events", 
            PubsubIO.readMessagesWithAttributesAndMessageId()
                .withIdAttribute(options.getMessageIdAttr())
                // set event time from a message attribute, milliseconds since the Unix epoch
                .withTimestampAttribute(options.getMessageTsAttr())
                .fromSubscription(options.getSubscription()));

        PCollectionTuple processedData = messages.apply("Extract CSV payload from pubsub message",
            ParDo.of(new ExtractPayload(lookupTable))
                .withOutputTags(STR_OUT, TupleTagList.of(STR_FAILURE_OUT))
                .withSideInputs(lookupTable));
            // this usually used with TextIO 
            // .apply("Set event timestamp value", WithTimestamps.of(new SetTimestamp())); 

        /* Realtime data analysis */
        // HBase/BigTable or Elasticsearch
        //
        // Use HBase/BigTable as an example, since we could show both wide and tall schema for 
        // window accumulating and disarding mode according to your specific scenario

        /* Various outputs for detailed data */
        /* 
         * @desc Use a composite trigger
         *   Combine
         * - triggering every early firing period of processing time
         * - util watermark passes
         * - then triggering any time a late datum arrives
         * - up to a garbage collection horizon of allowed lateness of event time
         * - all with accumulation strategy turned on that specified in code
         *
         *   We should end up with timing for: EARLY, ON_TIME & LATE
         */
        /*
        PCollection<String> healthData = processedData.get(STR_OUT)
            .apply(options.getWindowSize() + " window for healthy data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        AfterEach.inOrder(
                            Repeatedly.forever( 
                                AfterProcessingTime.pastFirstElementInPane() 
                                    // speculative result on a time basis
                                    .plusDelayOf(DurationUtils.parseDuration(
                                        options.getEarlyFiringPeriod()))
                                .orFinally(AfterWatermark.pastEndOfWindow()),
                            Repeatedly.forever(
                                AfterPane.elementCountAtLeast( // FIXME: should use other triggers
                                    options.getLateFiringCount().intValue()))
                        )
                    )
                    .discardingFiredPanes() // e.g. .accumulatingFiredPanes() etc.
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY))
            .apply("Append windowing information",
                ParDo.of(new AppendWindowInfo()));
        */

        /* REVISIT: A terse approach */
        PCollection<String> healthData = processedData.get(STR_OUT)
            .apply(options.getWindowSize() + " window for healthy data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime
                                    .pastFirstElementInPane() 
                                    .plusDelayOf(DurationUtils.parseDuration(options.getEarlyFiringPeriod())))
                            .withLateFirings(
                                AfterPane.elementCountAtLeast(
                                    options.getLateFiringCount().intValue()))
                    )
                    .discardingFiredPanes() // e.g. .accumulatingFiredPanes() etc.
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY))
            .apply("Append windowing information",
                ParDo.of(new AppendWindowInfo()));

        // REVISIT: we may apply differnet window for error data?
        PCollection<String> errData = processedData.get(STR_FAILURE_OUT)
            .apply(options.getWindowSize() + " window for error data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize()))));

        /* START - building realtime analytics */
        // we use dim1 as key to do the analysis
        // REVISIT: we applied the same windowing functions here, it could/should be different tho

        // window/panes accumulating mode, good for tall table
        processedData.get(STR_OUT)
            .apply(options.getWindowSize() 
                    + " window for healthy data in KV for real time analysis, accumulating mode",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime
                                    .pastFirstElementInPane() 
                                    .plusDelayOf(DurationUtils.parseDuration(options.getEarlyFiringPeriod())))
                            .withLateFirings(
                                AfterPane.elementCountAtLeast(
                                    options.getLateFiringCount().intValue()))
                    )
                    .accumulatingFiredPanes()
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY))
            .apply("Produce KV for aggregation operations", // produce PCollection<KV<String, String>>
                ParDo.of(new ProduceKv(options.getCsvDelimiter())))
            .apply("group by dim1 for analysis", // produce PCollection<KV<String, Iterable<String>>>
                GroupByKey.create())
            .apply("Produce HBase/Bigtable tall table",
                ParDo.of(new DoFn<KV<String, Iterable<String>>, Put>() {
                    @ProcessElement
                    public void processElement(ProcessContext ctx) {
                        final long processTs = System.currentTimeMillis();

                        // row key related value
                        StringBuilder sb = new StringBuilder(ctx.element().getKey());

                        // statistical data, choose the appropreate data types according to your case
                        final byte[] stats_cf = Bytes.toBytes("stats");
                        Integer count = 0;
                        Integer sum = 0;
                        Integer min = Integer.MAX_VALUE;
                        Integer max = Integer.MIN_VALUE;
                        Float avg = 0F;

                        Iterable<String> csvLines = ctx.element().getValue();

                        // i.e. "event_ts,thread_id,thread_name,seq,dim1,metrics1,process_ts,dim1_val"
                        for(String csvLine : csvLines) {
                            String[] csvValues = csvLine.split(",");
                            Integer metric = Integer.valueOf(csvValues[5]);

                            ++count;

                            sum += metric;

                            if (min >  metric)
                                min = metric;
                            
                            if (max < metric)
                                max = metric;
                        }

                        if (count > 0)
                            avg = sum.floatValue() / count;

                        ctx.output(
                            new Put(
                                Bytes.toBytes(sb.append('#')
                                    .append(Long.MAX_VALUE - processTs).toString())
                            ).addColumn(stats_cf, Bytes.toBytes("num_records"), processTs,
                                Bytes.toBytes(count.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("sum"), processTs,
                                Bytes.toBytes(sum.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("max"), processTs,
                                Bytes.toBytes(max.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("min"), processTs,
                                Bytes.toBytes(min.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("avg"), processTs,
                                Bytes.toBytes(avg.toString()))
                        );
                    }}))
                .apply("Append window information",
                    ParDo.of(new DoFn<Put, Mutation>() {
                        @ProcessElement
                        public void processElement(ProcessContext ctx, IntervalWindow window)
                            throws IllegalArgumentException {

                            final byte[] win_cf = Bytes.toBytes("window_info");

                            Put p = ctx.element();

                            p.addColumn(win_cf, Bytes.toBytes("window"),
                                    Bytes.toBytes(window.toString()))
                                .addColumn(win_cf, Bytes.toBytes("pane"),
                                    Bytes.toBytes(ctx.pane().toString()))
                                .addColumn(win_cf, Bytes.toBytes("pane_idx"),
                                    Bytes.toBytes(String.valueOf(ctx.pane().getIndex())))
                                .addColumn(win_cf, Bytes.toBytes("pane_nonspeculative_idx"),
                                    Bytes.toBytes(String.valueOf(ctx.pane().getNonSpeculativeIndex())))
                                .addColumn(win_cf, Bytes.toBytes("is_first"),
                                    Bytes.toBytes(String.valueOf(ctx.pane().isFirst())))
                                .addColumn(win_cf, Bytes.toBytes("is_last"),
                                    Bytes.toBytes(String.valueOf(ctx.pane().isLast())))
                                .addColumn(win_cf, Bytes.toBytes("timing"),
                                    Bytes.toBytes(ctx.pane().getTiming().toString()))
                                .addColumn(win_cf, Bytes.toBytes("win_start_ts"),
                                    Bytes.toBytes(String.valueOf(window.start().getMillis())))
                                .addColumn(win_cf, Bytes.toBytes("win_end_ts"),
                                    Bytes.toBytes(String.valueOf(window.end().getMillis())));

                            ctx.output(p);
                        }}))
                .apply("Insert into Bigtable, tall schema",
                    CloudBigtableIO.writeToTable(
                        new CloudBigtableTableConfiguration.Builder()
                            .withProjectId(options.getProject())
                            .withInstanceId(options.getBtInstanceId())
                            .withTableId(options.getBtTableIdTall())
                            .build()));

                /*
            */

        // window/panes disgarding mode, good for wide table
        processedData.get(STR_OUT)
            .apply(options.getWindowSize() 
                    + " window for healthy data in KV for real time analysis, disgarding mode",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime
                                    .pastFirstElementInPane() 
                                    .plusDelayOf(DurationUtils.parseDuration(options.getEarlyFiringPeriod())))
                            .withLateFirings(
                                AfterPane.elementCountAtLeast(
                                    options.getLateFiringCount().intValue()))
                    )
                    .discardingFiredPanes() 
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY))
            .apply("Produce KV for aggregation operations", // produce PCollection<KV<String, String>>
                ParDo.of(new ProduceKv(options.getCsvDelimiter())))
            .apply("group by dim1 for analysis", // produce PCollection<KV<String, Iterable<String>>>
                GroupByKey.create())
            .apply("Produce HBase/Bigtable wide table, window/pane info append to column names",
                ParDo.of(new DoFn<KV<String, Iterable<String>>, Mutation>() {
                    @ProcessElement
                    public void processElement(ProcessContext ctx, IntervalWindow window)
                             throws IllegalArgumentException {
                        final long processTs = System.currentTimeMillis();

                        // row key related value
                        StringBuilder sb = new StringBuilder(ctx.element().getKey());

                        // statistical data, choose the appropreate data types according to your case
                        final byte[] stats_cf = Bytes.toBytes("stats");
                        Integer count = 0;
                        Integer sum = 0;
                        Integer min = Integer.MAX_VALUE;
                        Integer max = Integer.MIN_VALUE;
                        Float avg = 0F;

                        Iterable<String> csvLines = ctx.element().getValue();

                        // i.e. "event_ts,thread_id,thread_name,seq,dim1,metrics1,process_ts,dim1_val"
                        for(String csvLine : csvLines) {
                            String[] csvValues = csvLine.split(",");
                            Integer metric = Integer.valueOf(csvValues[5]);

                            ++count;

                            sum += metric;

                            if (min >  metric)
                                min = metric;
                            
                            if (max < metric)
                                max = metric;
                        }

                        if (count > 0)
                            avg = sum.floatValue() / count;

                        String pane_idx_str = String.valueOf(ctx.pane().getIndex());

                        ctx.output(
                            new Put(
                                Bytes.toBytes(sb.append('#')
                                    .append(String.valueOf(window.start().getMillis()))
                                    .append('#')
                                    .append(String.valueOf(window.end().getMillis())).toString())
                            ).addColumn(stats_cf, Bytes.toBytes("num_records#" + pane_idx_str), 
                                processTs,
                                Bytes.toBytes(count.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("sum#" + pane_idx_str), 
                                processTs,
                                Bytes.toBytes(sum.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("max#" + pane_idx_str), 
                                processTs,
                                Bytes.toBytes(max.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("min#" + pane_idx_str), 
                                processTs,
                                Bytes.toBytes(min.toString()))
                            .addColumn(stats_cf, Bytes.toBytes("avg#" + pane_idx_str), 
                                processTs,
                                Bytes.toBytes(avg.toString()))
                        );
                    }}))
                .apply("Insert into Bigtable, wide schema",
                    CloudBigtableIO.writeToTable(
                        new CloudBigtableTableConfiguration.Builder()
                            .withProjectId(options.getProject())
                            .withInstanceId(options.getBtInstanceId())
                            .withTableId(options.getBtTableIdWide())
                            .build()));

        /* END - building realtime analytics */

        /* Elasticsearch */
        processedData.get(STR_OUT)
            .apply(options.getWindowSize() + " window for healthy data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime
                                    .pastFirstElementInPane() 
                                    .plusDelayOf(DurationUtils.parseDuration(options.getEarlyFiringPeriod())))
                            .withLateFirings(
                                AfterPane.elementCountAtLeast(
                                    options.getLateFiringCount().intValue()))
                    )
                    .discardingFiredPanes() // e.g. .accumulatingFiredPanes() etc.
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY))
            .apply("Prepare Elasticsearch Json data",
                ParDo.of(new DoFn<String, String>() {
                    private ObjectMapper mapper;

                    @Setup 
                    public void setup() {
                        mapper = new ObjectMapper();
                    }

                    @ProcessElement
                    public void processElement(ProcessContext ctx) throws Exception {
                        // "event_ts,thread_id,thread_name,seq,dim1,metrics1,process_ts,dim1_val"
                        String[] csvData = ctx.element().split(",");
                        Map<String, Object> jsonMap = new HashMap<>();
                        // FIXME: hardcoded
                        for (int i = 0; i < csvData.length; ++i) {
                            switch(i) {
                                case 0:
                                    // jsonMap.put("@timestamp", new Date(Long.parseLong(csvData[i])));
                                    jsonMap.put("@timestamp", 
                                        new java.sql.Timestamp(Long.parseLong(csvData[i])));
                                    break;
                                case 1:
                                    jsonMap.put("thread_id", csvData[i]);
                                    break;
                                case 2:
                                    jsonMap.put("thread_name", csvData[i]);
                                    break;
                                case 3:
                                    jsonMap.put("seq", Long.valueOf(csvData[i]));
                                    break;
                                case 4:
                                    jsonMap.put("dim1", csvData[i]);
                                    break;
                                case 5:
                                    jsonMap.put("metrics1", Long.valueOf(csvData[i]));
                                    break;
                                case 6:
                                    // jsonMap.put("process_ts", new Date(Long.parseLong(csvData[i])));
                                    jsonMap.put("process_ts", 
                                        new java.sql.Timestamp(Long.parseLong(csvData[i])));
                                    break;
                                case 7:
                                    jsonMap.put("dim1_val", csvData[i]);
                                    break;
                                default:
                            }
                        }

                        String json = mapper.writeValueAsString(jsonMap);
                        ctx.output(json);
                    }
                }))
            .apply("Append data to Elasticsearch",
                ElasticsearchIO.append()
                    .withMaxBatchSize(options.getEsMaxBatchSize())
                    .withMaxBatchSizeBytes(options.getEsMaxBatchBytes())
                    .withConnectionConf(
                        ConnectionConf.create(
                            options.getEsHost(),
                            options.getEsIndex())
                                .withUsername(options.getEsUser())
                                .withPassword(options.getEsPass())
                                .withNumThread(options.getEsNumThread()))
                                //.withTrustSelfSignedCerts(true)) // false by default
                    .withRetryConf(
                        RetryConf.create(6, Duration.standardSeconds(60))));
        /* END - Elasticsearch */

        healthData.apply("Write windowed healthy CSV files", 
            TextIO.write()
                .withNumShards(options.getNumShards())
                .withWindowedWrites()
                .to(
                    new WindowedFilenamePolicy(
                        options.getOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getCsvFilenameSuffix()
                    ))
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())));

        healthData.apply("Prepare table data for BigQuery",
            ParDo.of(
                new DoFn<String, TableRow>() {
                    @ProcessElement
                    public void processElement(ProcessContext ctx) {
                        String dataStr = ctx.element();

                        // REVISIT: damn ugly here, hard coded table schema
                        String[] cols = (
                                "event_ts,thread_id,thread_name,seq,dim1,metrics1" 
                                + ",process_ts,dim1_val"
                                + ",window,pane_info,pane_idx,pane_nonspeculative_idx"
                                + ",is_first,is_last,pane_timing,pane_event_ts"
                            ).split(",");

                        // REFISIT: options is NOT serializable, make a class for this transform
                        // String[] csvData = dataStr.split(options.getCsvDelimiter()); 
                        String[] csvData = dataStr.split(","); 

                        TableRow row = new TableRow();

                        // for god sake safety purpose
                        int loopCtr = 
                            cols.length <= csvData.length ? cols.length : csvData.length;

                        for (int i = 0; i < loopCtr; ++i) {
                            // deal with non-string field in BQ
                            switch (i) {
                                case 0: case 6:
                                case 15:
                                    row.set(cols[i], 
                                        TimeUnit.MILLISECONDS.toSeconds(
                                            Long.parseLong(csvData[i])));
                                    // row.set(cols[i], Long.parseLong(csvData[i]));
                                    // row.set(cols[i], Long.parseLong(csvData[i])/1000);
                                    // row.set(cols[i], Integer.parseInt(csvData[i]));
                                    break;
                                case 10: case 11:
                                    row.set(cols[i], Long.parseLong(csvData[i]));
                                    break;
                                case 3: case 5:
                                    row.set(cols[i], Integer.parseInt(csvData[i]));
                                    break;
                                case 12: case 13:
                                    row.set(cols[i], Boolean.parseBoolean(csvData[i]));
                                    break;
                                default:
                                    row.set(cols[i], csvData[i]);
                            }
                        } // End of dirty code

                        ctx.output(row);
                    }
                }
            ))
            .apply("Insert into BigQuery",
                BigQueryIO.writeTableRows()
                    .withSchema(
                        NestedValueProvider.of(
                            options.getBqSchema(),
                            new SerializableFunction<String, TableSchema>() {
                                @Override
                                public TableSchema apply(String jsonPath) {
                                    TableSchema tableSchema = new TableSchema();
                                    List<TableFieldSchema> fields = new ArrayList<>();
                                    SchemaParser schemaParser = new SchemaParser();
                                    JSONObject jsonSchema;

                                    try {
                                        jsonSchema = schemaParser.parseSchema(jsonPath);

                                        JSONArray bqSchemaJsonArray =
                                            jsonSchema.getJSONArray(BIGQUERY_SCHEMA);

                                        for (int i = 0; i < bqSchemaJsonArray.length(); i++) {
                                            JSONObject inputField = bqSchemaJsonArray.getJSONObject(i);
                                            TableFieldSchema field =
                                                new TableFieldSchema()
                                                    .setName(inputField.getString(NAME))
                                                    .setType(inputField.getString(TYPE));
                                            if (inputField.has(MODE)) {
                                                field.setMode(inputField.getString(MODE));
                                            }

                                            fields.add(field);
                                        }
                                        tableSchema.setFields(fields);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                    return tableSchema;
                                }
                            }))
                        .withTimePartitioning(
                            new TimePartitioning().setField("event_ts")
                                .setType("DAY")
                                .setExpirationMs(null)
                        )
                        .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
                        .withWriteDisposition(WriteDisposition.WRITE_APPEND)
                        .to(options.getBqOutputTable())
                        .withMethod(BigQueryIO.Write.Method.STREAMING_INSERTS)
                        .withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors())
                        .withCustomGcsTempLocation(options.getGcsTempLocation()));

        // Assume dealing with CSV payload, so basically convert CSV to Avro
        SchemaParser schemaParser = new SchemaParser();
        String avroSchemaJson = schemaParser.getAvroSchema(options.getAvroSchema().get());
        Schema avroSchema = new Schema.Parser().parse(avroSchemaJson);

        healthData.apply("Prepare Avro data",
                ParDo.of(new ConvertCsvToAvro(avroSchemaJson, options.getCsvDelimiter())))
            .setCoder(AvroCoder.of(GenericRecord.class, avroSchema))
            // .apply("Write Avro formatted data", AvroIO.writeGenericRecords(avroSchemaJson)
            .apply("Write Avro formatted data", AvroIO.writeGenericRecords(avroSchema)
                .to(
                    new WindowedFilenamePolicy(
                        options.getOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getAvroFilenameSuffix()
                    ))
                .withWindowedWrites()
                .withNumShards(options.getNumShards())
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())
                )
                .withCodec(CodecFactory.snappyCodec()));
        /*
                .withTempDirectory(NestedValueProvider.of(
                    options.getGcsTempLocation(),
                    (SerializableFunction<String, ResourceId>) input ->
                        FileBasedSink.convertToFileResourceIfPossible(input)
                ))
        */

        errData.apply("Write windowed error data in CSV format", 
            TextIO.write()
                .withNumShards(options.getNumShards())
                .withWindowedWrites()
                .to(
                    new WindowedFilenamePolicy(
                        options.getErrOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getCsvFilenameSuffix()
                    ))
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())));

        p.run();
        //p.run().waitUntilFinish();
    }


    public static void main(String... args) {
        PipelineOptionsFactory.register(BindiegoStreamingOptions.class);

        BindiegoStreamingOptions options = PipelineOptionsFactory
            .fromArgs(args)
            .withValidation()
            .as(BindiegoStreamingOptions.class);
        options.setStreaming(true);
        // options.setRunner(DataflowRunner.class);
        // options.setNumWorkers(2);
        // options.setUsePublicIps(true);
        
        try {
            run(options);
        } catch (Exception ex) {
            //System.err.println(ex);
            //ex.printStackTrace();
            logger.error(ex.getMessage(), ex);
        }
    }

    // Instantiate Logger
    private static final Logger logger = LoggerFactory.getLogger(BindiegoStreaming.class);

    /* tag for main output when extracting pubsub message payload*/
    private static final TupleTag<String> STR_OUT = 
        new TupleTag<String>() {};
    /* tag for failure output from the UDF */
    private static final TupleTag<String> STR_FAILURE_OUT = 
        new TupleTag<String>() {};

    private static final String BIGQUERY_SCHEMA = "BigQuery Schema";
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String MODE = "mode";
}
