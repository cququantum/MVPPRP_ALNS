import alns.AlnsConfig;
import alns.AlnsSolver;
import instance.Instance;
import model.SolveResult;
import originalModel.OriginalModelSolver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BatchOriginAlnsCsvExporter {
    private enum SolverMode {ORIGINAL, ALNS, COMPARE}

    private static final int[] INSTANCE_FAMILIES = new int[]{1, 2, 3, 4};
    private static final int[] CUSTOMER_COUNTS = new int[]{10};
    private static final int[] PERIOD_COUNTS = new int[]{3, 6, 9};
    private static final int[] VEHICLE_COUNTS = new int[]{2, 3};

    private static final int METHODS_PER_INSTANCE = 2;
    private static final double ALNS_TIME_LIMIT_SEC = 60.0;
    private static final String CSV_HEADER =
            "instance,method,status,feasible,optimal,objective,best_bound,gap,time_sec";
    private static final OutputStream DEV_NULL = new OutputStream() {
        @Override
        public void write(int b) {
            // discard
        }
    };
    private static final Path OUTPUT_CSV = Paths.get("results_origin_alns_24cases.csv");

    private BatchOriginAlnsCsvExporter() {
    }

    public static void main(String[] args) throws Exception {
        SolverMode solverMode = SolverMode.ALNS;
        for (String arg : args) {
            if (arg.startsWith("--solver=")) {
                solverMode = parseSolverMode(arg.substring("--solver=".length()));
            } else {
                throw new IllegalArgumentException("Unsupported argument: " + arg);
            }
        }

        String[] instancePaths = buildInstancePaths();
        Set<String> completedKeys = new HashSet<String>();
        ensureOutputFile(completedKeys);

        try (BufferedWriter writer = Files.newBufferedWriter(
                OUTPUT_CSV,
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND
        )) {
            int totalRows = instancePaths.length * methodsPerInstance(solverMode);
            int progress = countCompletedRows(completedKeys, solverMode);

            for (String instancePath : instancePaths) {
                String instanceName = instanceName(instancePath);
                final Instance ins = loadInstance(instancePath);

                if (solverMode == SolverMode.ORIGINAL || solverMode == SolverMode.COMPARE) {
                    progress = runAndWriteIfNeeded(
                            writer,
                            instanceName,
                            "origin",
                            completedKeys,
                            progress,
                            totalRows,
                            new SolveSupplier() {
                                @Override
                                public SolveResult get() {
                                    return new OriginalModelSolver().solve(ins);
                                }
                            }
                    );
                }

                if (solverMode == SolverMode.ALNS || solverMode == SolverMode.COMPARE) {
                    progress = runAndWriteIfNeeded(
                            writer,
                            instanceName,
                            "alns",
                            completedKeys,
                            progress,
                            totalRows,
                            new SolveSupplier() {
                                @Override
                                public SolveResult get() {
                                    AlnsConfig config = AlnsConfig.defaults().withTimeLimitSec(ALNS_TIME_LIMIT_SEC);
                                    return new AlnsSolver(config).solve(ins);
                                }
                            }
                    );
                }
            }
        }
    }

    private static SolverMode parseSolverMode(String x) {
        String v = x.trim().toLowerCase(Locale.ROOT);
        if ("original".equals(v)) {
            return SolverMode.ORIGINAL;
        }
        if ("alns".equals(v)) {
            return SolverMode.ALNS;
        }
        if ("compare".equals(v)) {
            return SolverMode.COMPARE;
        }
        throw new IllegalArgumentException("Unsupported --solver mode: " + x);
    }

    private static int methodsPerInstance(SolverMode solverMode) {
        return solverMode == SolverMode.COMPARE ? METHODS_PER_INSTANCE : 1;
    }

    private static int countCompletedRows(Set<String> completedKeys, SolverMode solverMode) {
        int count = 0;
        for (String key : completedKeys) {
            if (solverMode == SolverMode.COMPARE) {
                if (key.endsWith("|origin") || key.endsWith("|alns")) {
                    count++;
                }
            } else if (solverMode == SolverMode.ORIGINAL) {
                if (key.endsWith("|origin")) {
                    count++;
                }
            } else if (key.endsWith("|alns")) {
                count++;
            }
        }
        return count;
    }

    private static String[] buildInstancePaths() {
        ArrayList<String> paths = new ArrayList<String>();
        for (int family : INSTANCE_FAMILIES) {
            for (int customerCount : CUSTOMER_COUNTS) {
                for (int periodCount : PERIOD_COUNTS) {
                    for (int vehicleCount : VEHICLE_COUNTS) {
                        String path = String.format(
                                Locale.US,
                                "data/MVPRP/MVPRP%d_%d_%d_%d.txt",
                                family,
                                customerCount,
                                periodCount,
                                vehicleCount
                        );
                        if (!Files.exists(Paths.get(path))) {
                            throw new IllegalStateException("Instance file not found: " + path);
                        }
                        paths.add(path);
                    }
                }
            }
        }
        return paths.toArray(new String[0]);
    }

    private static Instance loadInstance(String instancePath) throws IOException {
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = true;
        return Instance.fromFile(instancePath, options);
    }

    private static int runAndWriteIfNeeded(
            BufferedWriter writer,
            String instanceName,
            String method,
            Set<String> completedKeys,
            int progress,
            int totalRows,
            SolveSupplier solveSupplier
    ) throws Exception {
        String key = buildKey(instanceName, method);
        if (completedKeys.contains(key)) {
            System.out.println("[" + progress + "/" + totalRows + "] skip: " + instanceName + " " + method);
            return progress;
        }

        SolveResult result = runQuietly(solveSupplier);
        writeRow(writer, instanceName, method, result);
        completedKeys.add(key);

        int newProgress = progress + 1;
        System.out.println("[" + newProgress + "/" + totalRows + "] done: " + instanceName + " " + method);
        return newProgress;
    }

    private static SolveResult runQuietly(SolveSupplier solveSupplier) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream silent = new PrintStream(DEV_NULL);
        try {
            System.setOut(silent);
            System.setErr(silent);
            return solveSupplier.get();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            silent.close();
        }
    }

    private static void ensureOutputFile(Set<String> completedKeys) throws IOException {
        if (!Files.exists(OUTPUT_CSV)) {
            writeHeader(OUTPUT_CSV);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(OUTPUT_CSV, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                writeHeader(OUTPUT_CSV);
                return;
            }
            if (!CSV_HEADER.equals(header)) {
                throw new IllegalStateException("Unexpected CSV header in " + OUTPUT_CSV + ": " + header);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> fields = parseCsvLine(line);
                if (fields.size() < 2) {
                    throw new IllegalStateException("Malformed CSV row in " + OUTPUT_CSV + ": " + line);
                }
                completedKeys.add(buildKey(fields.get(0), fields.get(1)));
            }
        }
    }

    private static void writeHeader(Path outputCsv) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                outputCsv,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            writer.write(CSV_HEADER);
            writer.write(System.lineSeparator());
            writer.flush();
        }
    }

    private static void writeRow(BufferedWriter writer, String instanceName, String method, SolveResult result) throws IOException {
        StringBuilder row = new StringBuilder(256);
        appendCsvField(row, instanceName);
        row.append(',');
        appendCsvField(row, method);
        row.append(',');
        appendCsvField(row, safeString(result.status));
        row.append(',');
        row.append(result.feasible);
        row.append(',');
        row.append(result.optimal);
        row.append(',');
        row.append(fmt(result.objective));
        row.append(',');
        row.append(fmt(result.bestBound));
        row.append(',');
        row.append(fmt(result.mipGap));
        row.append(',');
        row.append(fmt(result.solveTimeSec));
        row.append(System.lineSeparator());

        writer.write(row.toString());
        writer.flush();
    }

    private static void appendCsvField(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                sb.append('"');
            }
            sb.append(ch);
        }
        sb.append('"');
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static String instanceName(String instancePath) {
        String fileName = Paths.get(instancePath).getFileName().toString();
        if (fileName.endsWith(".txt")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private static String buildKey(String instanceName, String method) {
        return instanceName + "|" + method;
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        return String.format(Locale.US, "%.6f", value);
    }

    private interface SolveSupplier {
        SolveResult get() throws Exception;
    }
}
