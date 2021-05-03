package org.apache.sysds.hops.rewrite.dfp;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.rewrite.ProgramRewriteStatus;
import org.apache.sysds.hops.rewrite.StatementBlockRewriteRule;
import org.apache.sysds.parser.*;

import java.util.*;

import static org.apache.sysds.hops.rewrite.dfp.utils.DeepCopyHopsDag.deepCopyHopsDag;
import static org.apache.sysds.hops.rewrite.dfp.utils.Judge.*;
import static org.apache.sysds.hops.rewrite.dfp.utils.Reorder.reorder;

public class AnalyzeSymmetryMatrix extends StatementBlockRewriteRule {

    protected static final Log LOG = LogFactory.getLog(AnalyzeSymmetryMatrix.class.getName());

    public static boolean querySymmetry(String name) {
        if (symmetryMatrixNames.containsKey(name)) {
            return symmetryMatrixNames.get(name);
        }
        // 默认非对称
        return false;
    }

    @Override
    public boolean createsSplitDag() {
        return false;
    }

    @Override
    public List<StatementBlock> rewriteStatementBlock(StatementBlock sb, ProgramRewriteStatus state) {
        ArrayList<StatementBlock> res = new ArrayList<>();
        res.add(sb);
        return res;
    }

    @Override
    public List<StatementBlock> rewriteStatementBlocks(List<StatementBlock> sbs, ProgramRewriteStatus state) {
        //    System.out.println("ppp");
        sbs_iter(sbs);
        LOG.info("Symmetry Matrix{");
        for (Map.Entry<String, Boolean> entry : symmetryMatrixNames.entrySet()) {
            LOG.info(entry.getKey() + ":" + entry.getValue());
        }
        LOG.info("}");
        return sbs;
    }


    private void sbs_iter(List<StatementBlock> sbs) {
        for (StatementBlock sb : sbs) {
            if (sb instanceof WhileStatementBlock) {
                WhileStatement whileStatement = (WhileStatement) sb.getStatement(0);
                sbs_iter(whileStatement.getBody());
            } else if (sb instanceof IfStatementBlock) {
                IfStatement ifStatement = (IfStatement) sb.getStatement(0);
                sbs_iter(ifStatement.getIfBody());
                sbs_iter(ifStatement.getElseBody());
            } else if (sb != null) {
                for (Hop hop : sb.getHops()) {
                    processHop(hop);
                }
            }
        }
    }


    private void processHop(Hop hop) {
        //  System.out.println("process");
        if (isWrite(hop)) {
            //   System.out.println("twrite");
            Hop source = hop.getInput().get(0);
            if (source.isMatrix()) {
                String name = hop.getName();
                Hop copy = deepCopyHopsDag(source);
                copy = reorder(copy);
                boolean ans = checkHopDag(copy);
                registrySymmetry(name, ans);
            }
        }
    }

    private static boolean checkHopDag(Hop hop) {
        // aaa(hop);
        ArrayList<Hop> mults = new ArrayList<>();
        bbb(hop, mults);
        ArrayList<Boolean> booleans = new ArrayList<>();
        for (int i = 0; i < mults.size(); i++) booleans.add(false);
        for (int i = 0; i < mults.size(); i++) {
            if (booleans.get(i)) continue;
            Hop h = mults.get(i);
            if (isSymmetryMatrixMultChain(h)) {
                booleans.set(i, true);
            } else {
                for (int j = i + 1; j < mults.size(); j++) {
                    if (isTransposeMatrix(h, mults.get(j))) {
                        booleans.set(i, true);
                        booleans.set(j, true);
                    }
                }
            }
            if (!booleans.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static void bbb(Hop hop, ArrayList<Hop> mults) {
        if (hop.isScalar()) return;
        if (hop.getDim1() <= 1 && hop.getDim2() <= 1) return;
        if (isLeafMatrix(hop) || isAllOfMult(hop)) {
            mults.add(hop);
        } else {
            for (int i = 0; i < hop.getInput().size(); i++) {
                bbb(hop.getInput().get(i), mults);
            }
        }
    }

    private static boolean isSymmetryMatrixMultChain(Hop hop) {
        // 判断一个仅由乘法和转置构成的表达式结果是否是对称矩阵
        ArrayList<Pair<Hop, Boolean>> leaves = new ArrayList<>();
        getAllMatrix(hop, leaves);
        if (leaves.size() % 2 == 1) {
            Hop mid = leaves.get((leaves.size()) / 2).getLeft();
            if (!isSingleSymmetry(mid)) {
                return false;
            }
        }
        for (int l = 0, r = leaves.size() - 1; l < r; l++, r--) {
            Hop leftHop = leaves.get(l).getLeft();
            Hop rightHop = leaves.get(r).getLeft();
            boolean leftTranspose = leaves.get(l).getRight();
            boolean rightTranspose = leaves.get(r).getRight();
            if (leftHop != rightHop) {
                return false;
            }
            if (leftTranspose == rightTranspose
                    && !isSingleSymmetry(leftHop)) {
                return false;
            }
        }
        return true;
    }

    private static void getAllMatrix(Hop hop, ArrayList<Pair<Hop, Boolean>> hops) {
        if (isLeafMatrix(hop)) {
            hops.add(Pair.of(hop, false));
        } else if (HopRewriteUtils.isTransposeOperation(hop)
                && isLeafMatrix(hop.getInput().get(0))) {
            hops.add(Pair.of(hop.getInput().get(0), true));
        } else {
            for (int i = 0; i < hop.getInput().size(); i++) {
                getAllMatrix(hop.getInput().get(i), hops);
            }
        }
    }

    private static boolean isTransposeMatrix(Hop hop1, Hop hop2) {
        Hop trans2 = HopRewriteUtils.createTranspose(hop2);
        return isSame(hop1, trans2);
    }

    private static boolean isSingleSymmetry(Hop hop) {
        if (isDiagMatrix(hop)) return true; // 对角阵
        if (isReadSymmetry(hop)) return true; // 读入一个对称阵
        return false;
    }

    private static boolean isReadSymmetry(Hop hop) {
        if (isRead(hop)) {
            if (hop.isScalar()) {
                return true;
            } else {
                return querySymmetry(hop.getName());
            }
        } else {
            return false;
        }
    }


    public static HashMap<String, Boolean> symmetryMatrixNames = new HashMap<>();

    private static void registrySymmetry(String name, boolean isSymmetry) {
        // 一票否决
        if (isSymmetry) {
            if (!symmetryMatrixNames.containsKey(name))
                symmetryMatrixNames.put(name, true);
        } else {
            symmetryMatrixNames.put(name, false);
        }
        //   System.out.println("Registry " + name + " " + isSymmetry);
    }


}
