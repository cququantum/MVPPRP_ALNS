import instance.Instance;
import model.SolveResult;
import originalModel.OriginalModelSolver;

import java.io.IOException;
import java.util.Locale;

public class Main {

    public static void main(String[] args) {
        String[] instancePaths;
        if (args.length > 0) {
            instancePaths = args;
        } else {
            instancePaths = new String[]{"data/MVPRP/MVPRP2_10_3_2.txt"};
        }

        for (int idx = 0; idx < instancePaths.length; idx++) {
            if (idx > 0) {
                System.out.println();
            }
            runSingleInstance(instancePaths[idx]);
        }
    }

    private static void runSingleInstance(String instancePath) {
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = true;

        try {
            Instance ins = Instance.fromFile(instancePath, options);
            SolveResult result = new OriginalModelSolver().solve(ins);

            System.out.println("Instance: " + instancePath);
            System.out.println("n=" + ins.n + ", l=" + ins.l + ", K=" + ins.K + ", Q=" + format(ins.Q));
            System.out.println(result.toSummaryLine());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load instance file: " + instancePath, e);
        }
    }

    private static String format(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
