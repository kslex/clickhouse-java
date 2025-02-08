package com.clickhouse.client.datatypes;

import com.clickhouse.client.BaseIntegrationTest;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseProtocol;
import com.clickhouse.client.ClickHouseServerForTest;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandSettings;
import com.clickhouse.client.api.data_formats.internal.BinaryStreamReader;
import com.clickhouse.client.api.enums.Protocol;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.ClickHouseVersion;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DataTypeTests extends BaseIntegrationTest {

    private Client client;
    private InsertSettings settings;

    private boolean useClientCompression = false;

    private boolean useHttpCompression = false;

    private static final int EXECUTE_CMD_TIMEOUT = 10; // seconds

    public DataTypeTests(boolean useClientCompression, boolean useHttpCompression) {
        this.useClientCompression = useClientCompression;
        this.useHttpCompression = useHttpCompression;
    }

    public DataTypeTests() {
        this(false, false);
    }

    @BeforeMethod(groups = {"integration"})
    public void setUp() throws IOException {
        ClickHouseNode node = getServer(ClickHouseProtocol.HTTP);
        client = new Client.Builder()
                .addEndpoint(Protocol.HTTP, node.getHost(), node.getPort(), false)
                .setUsername("default")
                .setPassword(ClickHouseServerForTest.getPassword())
                .useNewImplementation(System.getProperty("client.tests.useNewImplementation", "true").equals("true"))
                .compressClientRequest(useClientCompression)
                .useHttpCompression(useHttpCompression)
                .build();
    }

    @AfterMethod(groups = {"integration"})
    public void tearDown() {
        client.close();
    }

    private <T> void writeReadVerify(String table, String tableDef, Class<T> dtoClass, List<T> data,
                                     BiConsumer<List<T>, T> rowVerifier) throws Exception {
        client.execute("DROP TABLE IF EXISTS " + table).get();
        client.execute(tableDef);

        final TableSchema tableSchema = client.getTableSchema(table);
        client.register(dtoClass, tableSchema);
        client.insert(table, data);
        final AtomicInteger rowCount = new AtomicInteger(0);
        client.queryAll("SELECT * FROM " + table, dtoClass, tableSchema).forEach(dto -> {
            rowVerifier.accept(data, dto);
            rowCount.incrementAndGet();
        });

        Assert.assertEquals(rowCount.get(), data.size());
    }

    @Test(groups = {"integration"})
    public void testNestedDataTypes() throws Exception {
        final String table = "test_nested_types";
        writeReadVerify(table,
                NestedTypesDTO.tblCreateSQL(table),
                NestedTypesDTO.class,
                Arrays.asList(new NestedTypesDTO(0, new Object[]{(short) 127, "test 1"}, new double[]{0.3d, 0.4d})),
                (data, dto) -> {
                    NestedTypesDTO dataDto = data.get(dto.getRowId());
                    Assert.assertEquals(dto.getTuple1(), dataDto.getTuple1());
                    Assert.assertEquals(dto.getPoint1(), dataDto.getPoint1());
                });
    }

    @Test(groups = {"integration"})
    public void testArrays() throws Exception {
        final String table = "test_arrays";
        writeReadVerify(table,
                DTOForArraysTests.tblCreateSQL(table),
                DTOForArraysTests.class,
                Arrays.asList(new DTOForArraysTests(
                        0, Arrays.asList("db", "fast"), new int[]{1, 2, 3}, new String[]{"a", "b", "c"})),
                (data, dto) -> {
                    DTOForArraysTests dataDto = data.get(dto.getRowId());
                    System.out.println(dto.getWords());
                    Assert.assertEquals(dto.getWords(), dataDto.getWords());
                    System.out.println(Arrays.asList(dto.getLetters()));
                    Assert.assertEquals(dto.getLetters(), dataDto.getLetters());
                    System.out.println(Arrays.asList(dto.getNumbers()));
                    Assert.assertEquals(dto.getNumbers(), dataDto.getNumbers());
                });
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DTOForArraysTests {
        private int rowId;

        private List<String> words;

        private int[] numbers;

        private String[] letters;

        public static String tblCreateSQL(String table) {
            return tableDefinition(table, "rowId Int16", "words Array(String)", "numbers Array(Int32)",
                    "letters Array(String)");
        }
    }

    @Test(groups = {"integration"})
    public void testVariantWithSimpleDataTypes() throws Exception {
        if (isVersionMatch("(,24.8]")) {
            return;
        }

        final String table = "test_variant_primitives";
        final DataTypesTestingPOJO sample = new DataTypesTestingPOJO();

        dataTypesLoop:
        for (ClickHouseDataType dataType : ClickHouseDataType.values()) {
            System.out.println("Testing " + dataType);
            client.execute("DROP TABLE IF EXISTS " + table).get();
            StringBuilder b = new StringBuilder(" CREATE TABLE ");
            b.append(table).append(" ( rowId Int64, field Variant(String, ").append(dataType.name());

            switch (dataType) {
                case String:
                case FixedString:
                case IntervalYear:
                case IntervalDay:
                case IntervalHour:
                case IntervalWeek:
                case IntervalMonth:
                case IntervalMinute:
                case IntervalSecond:
                case IntervalNanosecond:
                case IntervalMicrosecond:
                case IntervalQuarter:
                case IntervalMillisecond:
                case Nothing:
                case Variant:
                case JSON:
                case Object:
                    // skipped
                    continue dataTypesLoop;

                case Decimal:
                case Decimal32:
                case Decimal64:
                case Decimal128:
                case Decimal256:
                case Array:
                case Map:
                case Nested:
                case Tuple:
                case SimpleAggregateFunction:
                case AggregateFunction:
                case Enum8:
                case Enum16:
                    // tested separately
                    continue dataTypesLoop;

            }
            b.append(")) Engine = MergeTree ORDER BY ()");

            client.execute(b.toString(), (CommandSettings) new CommandSettings().serverSetting("enable_variant_type", "1"));
            client.register(DTOForVariantPrimitivesTests.class, client.getTableSchema(table));

            Object value = null;
            for (Method m : sample.getClass().getDeclaredMethods()) {
                if (m.getName().equalsIgnoreCase("get" + dataType.name())) {
                    value = m.invoke(sample);
                    System.out.println("selected " + value + " returned by method " + m.getName());
                    break;
                }
            }

            List<DTOForVariantPrimitivesTests> data = new ArrayList<>();
            data.add(new DTOForVariantPrimitivesTests(0, value));
            client.insert(table, data).get().close();

            List<GenericRecord> rows = client.queryAll("SELECT * FROM " + table);
            for (GenericRecord row : rows) {
                String strValue = row.getString("field");
                switch (dataType) {
                    case Date:
                    case Date32:
                        strValue = row.getLocalDate("field").toString();
                        break;
                    case DateTime64:
                    case DateTime:
                    case DateTime32:
                        strValue = row.getLocalDateTime("field").truncatedTo(ChronoUnit.SECONDS).toString();
                        value = ((LocalDateTime) value).truncatedTo(ChronoUnit.SECONDS).toString();
                        break;
                    case Point:
                        strValue = row.getGeoPoint("field").toString();
                        break;
                    case Ring:
                        strValue = row.getGeoRing("field").toString();
                        break;
                    case Polygon:
                        strValue = row.getGeoPolygon("field").toString();
                        break;
                    case MultiPolygon:
                        strValue = row.getGeoMultiPolygon("field").toString();
                        break;
                }
                System.out.println("field: " + strValue + " value " + value);
                if (value.getClass().isPrimitive()) {
                    Assert.assertEquals(strValue, String.valueOf(value));
                } else {
                    Assert.assertEquals(strValue, String.valueOf(value));
                }
            }
        }
    }

    @Data
    @AllArgsConstructor
    public static class DTOForVariantPrimitivesTests {
        private int rowId;
        private Object field;
    }

    @Test(groups = {"integration"})
    public void testVariantWithDecimals() throws Exception {
        testVariantWith("decimals", new String[]{"field Variant(String, Decimal(4, 4))"},
                new Object[]{
                        "10.2",
                        10.2d, // TODO: when f it gives 10.199
                },
                new String[]{
                        "10.2",
                        "10.2000",
                });
        testVariantWith("decimal32", new String[]{"field Variant(String, Decimal32(4))"},
                new Object[]{
                        "10.202",
                        10.1233d,
                },
                new String[]{
                        "10.202",
                        "10.1233",
                });
    }

    @Test(groups = {"integration"})
    public void testVariantWithArrays() throws Exception {
        testVariantWith("arrays", new String[]{"field Variant(String, Array(String))"},
                new Object[]{
                        "a,b",
                        new String[]{"a", "b"},
                        Arrays.asList("c", "d")
                },
                new String[]{
                        "a,b",
                        "[a, b]",
                        "[c, d]"
                });
        testVariantWith("arrays", new String[]{"field Variant(Array(String), Array(Int32))"},
                new Object[]{
                        new int[]{1, 2},
                        new String[]{"a", "b"},
                        Arrays.asList("c", "d")
                },
                new String[]{
                        "[1, 2]",
                        "[a, b]",
                        "[c, d]",
                });

        testVariantWith("arrays", new String[]{"field Variant(Array(Array(String)), Array(Array(Int32)))"},
                new Object[]{
                        new int[][]{ new int[] {1, 2}, new int[] { 3, 4}},
                        new String[][]{new String[]{"a", "b"}, new String[]{"c", "d"}},
                        Arrays.asList(Arrays.asList("e", "f"), Arrays.asList("j", "h"))
                },
                new String[]{
                        "[[1, 2], [3, 4]]",
                        "[[a, b], [c, d]]",
                        "[[e, f], [j, h]]",
                });
    }

    @Test(groups = {"integration"})
    public void testVariantWithMaps() throws Exception {
        Map<String, Byte> map1 = new HashMap<>();
        map1.put("key1", (byte) 1);
        map1.put("key2", (byte) 2);
        map1.put("key3", (byte) 3);

        testVariantWith("maps", new String[]{"field Variant(Map(String, String), Map(String, Int128))"},
                new Object[]{
                        map1
                },
                new String[]{
                        "{key1=1, key2=2, key3=3}",
                });


        Map<Integer, String> map2 = new HashMap<>();
        map2.put(1, "a");
        map2.put(2, "b");

        Map<String, String> map3 = new HashMap<>();
        map3.put("1", "a");
        map3.put("2", "b");

        testVariantWith("maps", new String[]{"field Variant(Map(Int32, String), Map(String, String))"},
                new Object[]{
                        map2,
                        map3
                },
                new String[]{
                        "{1=a, 2=b}",
                        "{1=a, 2=b}",
                });
    }

    @Test(groups = {"integration"})
    public void testVariantWithEnums() throws Exception {
        testVariantWith("enums", new String[]{"field Variant(Bool, Enum('stopped' = 1, 'running' = 2))"},
                new Object[]{
                        "stopped",
                        1,
                        "running",
                        2,
                        true,
                        false
                },
                new String[]{
                        "stopped",
                        "stopped",
                        "running",
                        "running",
                        "true",
                        "false"
                });
    }

    @Test(groups = {"integration"}, enabled = false)
    public void testVariantWithTuple() throws Exception {
        // TODO: same as array
        testVariantWith("arrays", new String[]{"field Variant(String, Tuple(Int32, Float32))"},
                new Object[]{
                        "10,0.34",
                        new Object[]{10, 0.34f}
                },
                new String[]{
                        "10,0.34",
                        "(10,0.34)",
                });
    }



    @Test(groups = {"integration"})
    public void testDynamicWithPrimitives() throws Exception {

        if (isVersionMatch("(,24.8]")) {
            return;
        }

        final String table = "test_dynamic_primitives";
        final DataTypesTestingPOJO sample = new DataTypesTestingPOJO();

        client.execute("DROP TABLE IF EXISTS " + table).get();
        String createTableStatement = " CREATE TABLE " + table + "( rowId Int64, field Dynamic ) " +
                "Engine = MergeTree ORDER BY ()";

        client.execute(createTableStatement, (CommandSettings) new CommandSettings().serverSetting("enable_dynamic_type", "1"));
        client.register(DTOForDynamicPrimitivesTests.class, client.getTableSchema(table));

        int rowId = 0;
        for (ClickHouseDataType dataType : ClickHouseDataType.values()) {
            System.out.println("Testing dynamic with " + dataType + " values");

            switch (dataType) {
                case Date:
                case Date32:
                case DateTime:
                case DateTime32:
                case DateTime64:
                case Enum8:
                case Enum16:
                    continue;
                default:
            }

            Object value = null;
            for (Method m : sample.getClass().getDeclaredMethods()) {
                if (m.getName().equalsIgnoreCase("get" + dataType.name())) {
                    value = m.invoke(sample);
                    System.out.println("selected " + value + " returned by method " + m.getName());
                    break;
                }
            }

            List<DTOForDynamicPrimitivesTests> data = new ArrayList<>();
            data.add(new DTOForDynamicPrimitivesTests(rowId++, value));
            try {
                client.insert(table, data).get().close();
            } catch (Exception e) {
                System.out.println("Failed for " + dataType + ": " + e.getMessage());
                continue;
            }
            List<GenericRecord> rows = client.queryAll("SELECT * FROM " + table + " ORDER BY rowId DESC  ");
            GenericRecord row = rows.get(0);
                String strValue = row.getString("field");
                switch (dataType) {
                    case Date:
                    case Date32:
                        strValue = row.getLocalDate("field").toString();
                        break;
                    case DateTime64:
                    case DateTime:
                    case DateTime32:
                        strValue = row.getLocalDateTime("field").truncatedTo(ChronoUnit.SECONDS).toString();
                        value = ((LocalDateTime) value).truncatedTo(ChronoUnit.SECONDS).toString();
                        break;
                    case Point:
                        strValue = row.getGeoPoint("field").toString();
                        break;
                    case Ring:
                        strValue = row.getGeoRing("field").toString();
                        break;
                    case Polygon:
                        strValue = row.getGeoPolygon("field").toString();
                        break;
                    case MultiPolygon:
                        strValue = row.getGeoMultiPolygon("field").toString();
                        break;
                }
                System.out.println("field: " + strValue + " value " + value);
                if (value.getClass().isPrimitive()) {
                    Assert.assertEquals(strValue, String.valueOf(value));
                } else {
                    Assert.assertEquals(strValue, String.valueOf(value));
                }
        }
    }

    @Data
    @AllArgsConstructor
    public static class DTOForDynamicPrimitivesTests {
        private int rowId;
        private Object field;
    }

    private void testVariantWith(String withWhat, String[] fields, Object[] values, String[] expectedStrValues) throws Exception {
        if (isVersionMatch("(,24.8]")) {
            return;
        }

        String table = "test_variant_with_" + withWhat;
        String[] actualFields = new String[fields.length + 1];
        actualFields[0] = "rowId Int32";
        System.arraycopy(fields, 0, actualFields, 1, fields.length);
        client.execute("DROP TABLE IF EXISTS " + table).get();
        client.execute(tableDefinition(table, actualFields), (CommandSettings) new CommandSettings().serverSetting("enable_variant_type", "1")).get();

        client.register(DTOForVariantPrimitivesTests.class, client.getTableSchema(table));

        List<DTOForVariantPrimitivesTests> data = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            data.add(new DTOForVariantPrimitivesTests(i, values[i]));
        }
        client.insert(table, data).get().close();

        List<GenericRecord> rows = client.queryAll("SELECT * FROM " + table);
        for (GenericRecord row : rows) {
            System.out.println("> " + row.getString("field"));
            Assert.assertEquals(row.getString("field"), expectedStrValues[row.getInteger("rowId")]);
        }
    }

    public static String tableDefinition(String table, String... columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE " + table + " ( ");
        Arrays.stream(columns).forEach(s -> {
            sb.append(s).append(", ");
        });
        sb.setLength(sb.length() - 2);
        sb.append(") Engine = MergeTree ORDER BY ()");
        return sb.toString();
    }

    public boolean isVersionMatch(String versionExpression) {
        List<GenericRecord> serverVersion = client.queryAll("SELECT version()");
        return ClickHouseVersion.of(serverVersion.get(0).getString(1)).check(versionExpression);
    }
}
