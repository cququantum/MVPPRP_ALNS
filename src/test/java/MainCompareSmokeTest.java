import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MainCompareSmokeTest extends TestCase {

    public void testCompareModeProducesGapToStdout() throws Exception {
        Path temp = Files.createTempFile("mvprp-small", ".txt");
        Files.write(
                temp,
                (
                        "InstanceType:\t1\n" +
                        "CustomerNumber:\t2\n" +
                        "PeriodNumber:\t2\n" +
                        "VechileNumber:\t2\n" +
                        "VehicleCapacity:\t25\n" +
                        "Supplier COORDX COORDY InitLevel MaxLevel ProdCapacity HoldCost VarCost FixCost\n" +
                        "0 0 0 10 40 30 1 2 5\n" +
                        "Retailer COORDX COORDY InitLevel MaxLevel Demand HoldCost\n" +
                        "1 3 0 5 20 5 1\n" +
                        "2 0 4 5 20 5 1\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        System.setOut(new PrintStream(buf));
        try {
            Main.main(new String[]{
                    "--solver=compare",
                    "--alns-time-sec=1",
                    temp.toString()
            });
        } finally {
            System.setOut(oldOut);
        }

        String output = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("Comparison | exactGapPct="));
    }
}
