package alns;

import instance.Instance;
import junit.framework.TestCase;

public final class ProductionReoptimizerTest extends TestCase {

    public void testProductionReoptimizerFindsFeasiblePlan() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(2, 1, 1, 0);
        solution.insertVisitAt(1, 2, 0, 0);
        solution.insertVisitAt(2, 2, 1, 0);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        assertTrue(evaluator.evaluateStructure(ins, solution).structureFeasible);

        ProductionReoptimizer.Result prod = new ProductionReoptimizer().optimize(ins, solution, 5.0);
        assertTrue(prod.feasible);
        solution.setProductionPlan(prod);
        assertTrue(evaluator.evaluateFull(ins, solution).feasible);
        assertTrue(solution.objective > 0.0);
    }
}
