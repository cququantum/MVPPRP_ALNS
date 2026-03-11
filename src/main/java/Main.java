import alns.AlnsConfig;
import alns.AlnsSolver;
import instance.Instance;
import model.SolveResult;
import originalModel.OriginalModelSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

public class Main {
    private enum SolverMode { ORIGINAL, ALNS, COMPARE }
    private static final String[] DEFAULT_COMPARE_INSTANCES = new String[]{
            "data/MVPRP/MVPRP1_10_3_2.txt",
            "data/MVPRP/MVPRP2_10_3_2.txt",
            "data/MVPRP/MVPRP3_10_3_2.txt",
            "data/MVPRP/MVPRP4_10_3_2.txt"
    };

    public static void main(String[] args) {
        SolverMode solverMode = SolverMode.ORIGINAL;
        AlnsConfig alnsConfig = AlnsConfig.defaults();
        ArrayList<String> instancePathList = new ArrayList<String>();

        for (String arg : args) {
            if (arg.startsWith("--solver=")) {
                solverMode = parseSolverMode(arg.substring("--solver=".length()));
            } else if (arg.startsWith("--alns-seed=")) {
                alnsConfig = alnsConfig.withSeed(Long.parseLong(arg.substring("--alns-seed=".length())));
            } else if (arg.startsWith("--alns-time-sec=")) {
                alnsConfig = alnsConfig.withTimeLimitSec(Double.parseDouble(arg.substring("--alns-time-sec=".length())));
            } else {
                instancePathList.add(arg);
            }
        }

        String[] instancePaths;
        if (!instancePathList.isEmpty()) {
            instancePaths = instancePathList.toArray(new String[0]);
        } else if (solverMode == SolverMode.COMPARE) {
            instancePaths = DEFAULT_COMPARE_INSTANCES;
        } else {
            instancePaths = new String[]{"data/MVPRP/MVPRP2_10_3_2.txt"};
        }

        ArrayList<String> compareCsvRows = new ArrayList<String>();
        if (solverMode == SolverMode.COMPARE) {
            compareCsvRows.add("instance,exactObj,exactTimeSec,alnsObj,alnsTimeSec,gapPct,seed,timeLimitSec");
        }

        for (int idx = 0; idx < instancePaths.length; idx++) {
            if (idx > 0) {
                System.out.println();
            }
            runSingleInstance(instancePaths[idx], solverMode, alnsConfig, compareCsvRows);
        }

        if (solverMode == SolverMode.COMPARE) {
            writeCompareCsv(compareCsvRows);
        }
    }

    private static void runSingleInstance(
            String instancePath,
            SolverMode solverMode,
            AlnsConfig alnsConfig,
            ArrayList<String> compareCsvRows
    ) {
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = true;

        try {
            Instance ins = Instance.fromFile(instancePath, options);
            System.out.println("Instance: " + instancePath);
            System.out.println("n=" + ins.n + ", l=" + ins.l + ", K=" + ins.K + ", Q=" + format(ins.Q));

            if (solverMode == SolverMode.ORIGINAL) {
                SolveResult result = new OriginalModelSolver().solve(ins);
                System.out.println(result.toSummaryLine());
                return;
            }

            if (solverMode == SolverMode.ALNS) {
                SolveResult result = new AlnsSolver(alnsConfig).solve(ins);
                System.out.println(result.toSummaryLine());
                return;
            }

            SolveResult exact = new OriginalModelSolver().solve(ins);
            SolveResult alns = new AlnsSolver(alnsConfig).solve(ins);
            System.out.println(exact.toSummaryLine());
            System.out.println(alns.toSummaryLine());

            double gapPct = exactGapPct(exact, alns);
            System.out.println(
                    "Comparison | exactGapPct=" + format(gapPct)
                            + " | objDelta(alns-exact)=" + format(alns.objective - exact.objective)
                            + " | exactTimeSec=" + format(exact.solveTimeSec)
                            + " | alnsTimeSec=" + format(alns.solveTimeSec)
            );
            compareCsvRows.add(
                    instancePath + ","
                            + format(exact.objective) + ","
                            + format(exact.solveTimeSec) + ","
                            + format(alns.objective) + ","
                            + format(alns.solveTimeSec) + ","
                            + format(gapPct) + ","
                            + alnsConfig.seed + ","
                            + format(alnsConfig.timeLimitSec)
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to load instance file: " + instancePath, e);
        }
    }

    private static void writeCompareCsv(ArrayList<String> rows) {
        try {
            Files.write(Path.of("alns_vs_original.csv"), rows, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write compare csv", e);
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

    private static double exactGapPct(SolveResult exact, SolveResult alns) {
        return 100.0 * (alns.objective - exact.objective) / exact.objective;
    }

    private static String format(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
