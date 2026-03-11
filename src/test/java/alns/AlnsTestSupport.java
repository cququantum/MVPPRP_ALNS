package alns;

import instance.Instance;

import java.io.IOException;

final class AlnsTestSupport {
    private AlnsTestSupport() {
    }

    static Instance smallInstance() throws IOException {
        String txt =
                "InstanceType:\t1\n" +
                "CustomerNumber:\t2\n" +
                "PeriodNumber:\t2\n" +
                "VechileNumber:\t2\n" +
                "VehicleCapacity:\t25\n" +
                "Supplier COORDX COORDY InitLevel MaxLevel ProdCapacity HoldCost VarCost FixCost\n" +
                "0 0 0 10 40 30 1 2 5\n" +
                "Retailer COORDX COORDY InitLevel MaxLevel Demand HoldCost\n" +
                "1 3 0 5 20 5 1\n" +
                "2 0 4 5 20 5 1\n";
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = true;
        return Instance.fromString(txt, options);
    }
}
