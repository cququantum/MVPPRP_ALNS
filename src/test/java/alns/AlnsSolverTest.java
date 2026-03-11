package alns;

import instance.Instance;
import junit.framework.TestCase;
import model.SolveResult;

public final class AlnsSolverTest extends TestCase {

    public void testSolverIsReproducibleForSameSeed() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsConfig config = AlnsConfig.defaults().withTimeLimitSec(1.0).withSeed(123L);

        SolveResult a = new AlnsSolver(config).solve(ins);
        SolveResult b = new AlnsSolver(config).solve(ins);

        assertTrue(a.feasible);
        assertTrue(b.feasible);
        assertEquals(a.objective, b.objective, 1e-6);
    }
}
