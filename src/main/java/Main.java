import alns.AlnsConfig;
import alns.AlnsSolver;
import instance.Instance;
import model.SolveResult;
import originalModel.OriginalModelSolver;

import java.io.IOException;
import java.util.Locale;

public class Main {
    private enum SolverMode { ORIGINAL, ALNS, COMPARE }
    private static final String DEFAULT_COMPARE_INSTANCE = "data/MVPRP/MVPRP1_10_3_2.txt";

    public static void main(String[] args) {
        SolverMode solverMode = SolverMode.COMPARE;
        AlnsConfig alnsConfig = AlnsConfig.defaults();
        String instancePath = null;

        for (String arg : args) {
            if (arg.startsWith("--solver=")) {
                solverMode = parseSolverMode(arg.substring("--solver=".length()));
            } else if (arg.startsWith("--alns-seed=")) {
                alnsConfig = alnsConfig.withSeed(Long.parseLong(arg.substring("--alns-seed=".length())));
            } else if (arg.startsWith("--alns-time-sec=")) {
                alnsConfig = alnsConfig.withTimeLimitSec(Double.parseDouble(arg.substring("--alns-time-sec=".length())));
            } else {
                if (instancePath != null) {
                    throw new IllegalArgumentException("Main supports exactly one instance path.");
                }
                instancePath = arg;
            }
        }

        if (instancePath == null) {
            if (solverMode == SolverMode.COMPARE) {
                instancePath = DEFAULT_COMPARE_INSTANCE;
            } else {
                instancePath = "data/MVPRP/MVPRP2_10_3_2.txt";
            }
        }

        runSingleInstance(instancePath, solverMode, alnsConfig);
    }

    private static void runSingleInstance(String instancePath, SolverMode solverMode, AlnsConfig alnsConfig) {
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
        } catch (IOException e) {
            throw new RuntimeException("Failed to load instance file: " + instancePath, e);
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
