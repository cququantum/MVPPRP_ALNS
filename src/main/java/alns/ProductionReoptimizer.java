package alns;

import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import instance.Instance;
import model.CplexConfig;

public final class ProductionReoptimizer {

    public static final class Result {
        public final boolean feasible;
        public final String status;
        public final double objective;
        public final double[] y;
        public final double[] p;
        public final double[] p0;
        public final double[] i0;

        Result(boolean feasible, String status, double objective, double[] y, double[] p, double[] p0, double[] i0) {
            this.feasible = feasible;
            this.status = status;
            this.objective = objective;
            this.y = y;
            this.p = p;
            this.p0 = p0;
            this.i0 = i0;
        }
    }

    public Result optimize(Instance ins, AlnsSolution solution, double timeLimitSec) {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setParam(IloCplex.Param.TimeLimit, Math.max(0.05, Math.min(timeLimitSec, 5.0)));
            cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, CplexConfig.MIP_GAP);
            if (!CplexConfig.LOG_TO_CONSOLE) {
                cplex.setOut(null);
                cplex.setWarning(null);
            }

            IloNumVar[] y = new IloNumVar[ins.l + 1];
            IloNumVar[] p = new IloNumVar[ins.l + 1];
            IloNumVar[] p0 = new IloNumVar[ins.l + 1];
            IloNumVar[] i0 = new IloNumVar[ins.l + 1];

            for (int t = 1; t <= ins.l; t++) {
                y[t] = cplex.boolVar("alns_y_" + t);
                p[t] = cplex.numVar(0.0, Double.MAX_VALUE, "alns_p_" + t);
                p0[t] = cplex.numVar(0.0, Double.MAX_VALUE, "alns_P0_" + t);
                i0[t] = cplex.numVar(0.0, Double.MAX_VALUE, "alns_I0_" + t);
            }

            IloLinearNumExpr obj = cplex.linearNumExpr();
            for (int t = 1; t <= ins.l; t++) {
                obj.addTerm(ins.u, p[t]);
                obj.addTerm(ins.f, y[t]);
                obj.addTerm(ins.h0, i0[t]);
                obj.addTerm(ins.hp, p0[t]);
            }
            cplex.addMinimize(obj);

            for (int t = 1; t <= ins.l; t++) {
                IloLinearNumExpr finished = cplex.linearNumExpr();
                if (t > 1) {
                    finished.addTerm(1.0, p0[t - 1]);
                }
                finished.addTerm(1.0, p[t]);
                finished.addTerm(-1.0, p0[t]);
                cplex.addEq(finished, ins.dt[t] - ((t == 1) ? ins.P00 : 0.0), "alns_finished_" + t);

                IloLinearNumExpr cap = cplex.linearNumExpr();
                cap.addTerm(1.0, p[t]);
                cap.addTerm(-ins.C, y[t]);
                cplex.addLe(cap, 0.0, "alns_prod_cap_" + t);
                cplex.addLe(p0[t], ins.Lp, "alns_finished_cap_" + t);
            }

            for (int t = 1; t <= ins.l; t++) {
                IloLinearNumExpr raw = cplex.linearNumExpr();
                double rhs = -((t == 1) ? ins.I00 : 0.0);
                if (t > 1) {
                    raw.addTerm(1.0, i0[t - 1]);
                }
                for (int i = 1; i <= ins.n; i++) {
                    rhs -= solution.q[i][t];
                }
                raw.addTerm(-ins.k, p[t]);
                raw.addTerm(-1.0, i0[t]);
                cplex.addEq(raw, rhs, "alns_raw_" + t);
                cplex.addLe(i0[t], ins.L0, "alns_raw_cap_" + t);
            }

            boolean solved = cplex.solve();
            if (!solved) {
                return new Result(false, cplex.getStatus().toString(), Double.NaN,
                        new double[ins.l + 1], new double[ins.l + 1], new double[ins.l + 1], new double[ins.l + 1]);
            }

            double[] yVal = new double[ins.l + 1];
            double[] pVal = new double[ins.l + 1];
            double[] p0Val = new double[ins.l + 1];
            double[] i0Val = new double[ins.l + 1];
            for (int t = 1; t <= ins.l; t++) {
                yVal[t] = cplex.getValue(y[t]);
                pVal[t] = cplex.getValue(p[t]);
                p0Val[t] = cplex.getValue(p0[t]);
                i0Val[t] = cplex.getValue(i0[t]);
            }
            return new Result(true, cplex.getStatus().toString(), cplex.getObjValue(), yVal, pVal, p0Val, i0Val);
        } catch (IloException e) {
            throw new RuntimeException("Failed to optimize production plan for ALNS", e);
        }
    }
}
