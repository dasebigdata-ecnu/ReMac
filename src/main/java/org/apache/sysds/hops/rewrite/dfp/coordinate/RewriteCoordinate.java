package org.apache.sysds.hops.rewrite.dfp.coordinate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.cost.CostEstimationWrapper;
import org.apache.sysds.hops.rewrite.*;
import org.apache.sysds.hops.rewrite.dfp.AnalyzeSymmetryMatrix;
import org.apache.sysds.hops.rewrite.dfp.DisjointSet;
import org.apache.sysds.hops.rewrite.dfp.Leaf;
import org.apache.sysds.hops.rewrite.dfp.utils.MyExplain;
import org.apache.sysds.hops.rewrite.dfp.utils.Prime;
import org.apache.sysds.parser.*;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.utils.Explain;

import java.util.*;

import static org.apache.sysds.hops.rewrite.dfp.utils.DeepCopyHopsDag.deepCopyHopsDag;
import static org.apache.sysds.hops.rewrite.dfp.utils.Judge.isLeafMatrix;
import static org.apache.sysds.hops.rewrite.dfp.utils.Reorder.reorder;

public class RewriteCoordinate extends StatementBlockRewriteRule {


    private static DisjointSet djs = new DisjointSet(1000);
    private static HashMap<Long, Integer> hopId2LeafIndex = new HashMap<>();
    private static ArrayList<Leaf> leaves = new ArrayList<>();
    private static ArrayList<Range> blockRanges = new ArrayList<>();
    private static HashMap<HashKey, ArrayList<Range>> hash2Ranges = new HashMap<>();
    private static ExecutionContext ec;
    public static StatementBlock statementBlock;

    private static RewriteMatrixMultChainOptimization rewriteMatrixMultChainOptimization = new RewriteMatrixMultChainOptimization();
    private static RewriteCommonSubexpressionElimination rewriteCommonSubexpressionElimination = new RewriteCommonSubexpressionElimination();
    private static final Log LOG = LogFactory.getLog(RewriteCoordinate.class.getName());

    // <configuration>
    private static boolean showBlock = false;
    private static boolean showSingleCse = false;
    private static boolean showMultiCse = false;
    private static boolean showCost = false;
    private static boolean showMinCostHop = false;
    // </configuration>

    public static Hop rewiteHopDag(Hop root) {
        try {

            // 1. 深拷贝，格式化
            Hop hop = deepCopyHopsDag(root);
            hop = reorder(hop);

            hop.resetVisitStatusForced(new HashSet<>());
            LOG.info("start coordinate " + MyExplain.myExplain(hop) );

            prepare();

            findAllLeaf(hop, new ArrayList<>(), 0);

            genBlocks();

            if (leaves.size() < 20) return root;

            ArrayList<SingleCse> singleCses = genSingleCse();

            // 构造出所有的MultiCse
            ArrayList<MultiCse> multiCses = genMultiCse(singleCses);

            Hop result = genAndSelectHop(multiCses, hop,root);

            return result;

        } catch (Exception e) {
            e.printStackTrace();
        }
        return root;
    }

    private static int findAllLeaf(Hop hop, ArrayList<Integer> path, int depth) {
        if (isLeafMatrix(hop) ||
                (HopRewriteUtils.isTransposeOperation(hop) && isLeafMatrix(hop.getInput().get(0)))) {
            int index = leaves.size();
            hopId2LeafIndex.put(hop.getHopID(), index);
            Leaf leaf = new Leaf(hop, path, depth);
            leaves.add(leaf);
            return index;
        } else {
            if (HopRewriteUtils.isMatrixMultiply(hop)) {
                if (path.size() <= depth) path.add(0);
                else path.set(depth, 0);
                int l = findAllLeaf(hop.getInput().get(0), path, depth + 1);
                path.set(depth, 1);
                int r = findAllLeaf(hop.getInput().get(1), path, depth + 1);
                if (l >= 0 && r >= 0) {
                    djs.merge(l, r);
                    return l;
                }
            } else {
                for (int i = 0; i < hop.getInput().size(); i++) {
                    if (path.size() <= depth) path.add(i);
                    else path.set(depth, i);
                    findAllLeaf(hop.getInput().get(i), path, depth + 1);
                }
            }
        }
        return -1;
    }

    private static void prepare() {

        djs = new DisjointSet(1000);
        hopId2LeafIndex = new HashMap<>();
        leaves = new ArrayList<>();
        blockRanges = new ArrayList<>();
        hash2Ranges = new HashMap<>();

    }

    private static void genBlocks() {

        // 3. 把叶子根据并查集分为多个块
        for (int i = 0; i < leaves.size(); i++) {
            if (djs.find(i) == i) {
                int l = i, r = i;
                while (l - 1 >= 0 && djs.find(l - 1) == djs.find(i)) l--;
                while (r + 1 < leaves.size() && djs.find(r + 1) == djs.find(i)) r++;
                blockRanges.add(Range.of(l, r));
                if (showBlock)
                    LOG.info("Range " + l + " " + r + " " + getRangeName(l, r));
            }
        }
        // 4. 求出每个区间的哈希值，并把哈希值相同的汇聚起来
        for (Range block : blockRanges) {
            for (int l = block.left; l <= block.right; l++) {
                for (int r = l + 1; r <= block.right; r++) {
                    long first = rangeHash1(l, r);
                    long second = rangeHash2(l, r);
                    if (first < second) {
                        Long tmp = first;
                        first = second;
                        second = tmp;
                    }
                    HashKey hash = HashKey.of(first, second);
                    if (!hash2Ranges.containsKey(hash))
                        hash2Ranges.put(hash, new ArrayList<>());
                    hash2Ranges.get(hash).add(Range.of(l, r));
                }
            }
        }
        // 过滤掉不是公共子式的区间
        for (Iterator<Map.Entry<HashKey, ArrayList<Range>>> it = hash2Ranges.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<HashKey, ArrayList<Range>> e = it.next();
            ArrayList<Range> list = e.getValue();
            if (list.size() < 2 || list.get(0).right >= list.get(list.size() - 1).left) {
                it.remove();
            }
        }
    }

    private static ArrayList<SingleCse> genSingleCse() {
        // 构造出所有的SingleCse
        ArrayList<SingleCse> result = new ArrayList<>();
        for (Map.Entry<HashKey, ArrayList<Range>> e : hash2Ranges.entrySet()) {
            ArrayList<SingleCse> singleCses = genSingleCseFromRanges(e.getKey(), e.getValue());
            result.addAll(singleCses);
        }
        if (showSingleCse) {
            for (SingleCse singleCse : result) {
                LOG.info(singleCse);
            }
        }
        LOG.info("number of single cse = " + result.size());
        return result;
    }

    private static ArrayList<SingleCse> genSingleCseFromRanges(HashKey hash, ArrayList<Range> ranges) {
        ArrayList<SingleCse> result = new ArrayList<>();
        for (int index = 0; index < ranges.size(); index++) {
            SingleCse tmp = new SingleCse(hash, new ArrayList<>(), ranges.get(index), index);
            tmp.name = getRangeName(ranges.get(index));
            result.add(tmp);
        }
        for (int i = 0; i < result.size(); i++) {
            SingleCse frontSC = result.get(i);
            for (int index = frontSC.last_index + 1; index < ranges.size(); index++) {
                Range rangeA = ranges.get(index);
                boolean ok = true;
                for (int k = 0; ok && k < frontSC.ranges.size(); k++) {
                    Range rangeB = frontSC.ranges.get(k);
                    if (rangeA.intersect(rangeB)) {
                        ok = false;
                    }
                }
                if (ok) {
                    SingleCse newSC = new SingleCse(hash, frontSC.ranges, rangeA, index);
                    newSC.name = frontSC.name;
                    result.add(newSC);
                }
            }
        }
        for (int j = 0; j < ranges.size(); j++) {
            result.remove(0);
        }
        return result;
    }

    private static ArrayList<MultiCse> genMultiCse(ArrayList<SingleCse> singleCse) {
        long start = System.nanoTime();

        singleCse.sort((SingleCse a, SingleCse b) -> {
            Range ra = a.ranges.get(0);
            int la = ra.right - ra.left;
            Range rb = b.ranges.get(0);
            int lb = rb.right - rb.left;
            return lb - la;
        });
//        for (int j = 0; j < singleCse.size(); j++) {
//            LOG.info(singleCse.get(j).ranges.get(0));
//        }

        ArrayList<MultiCse> result = new ArrayList<>();
        for (int j = 0; j < singleCse.size(); j++) {
            MultiCse c = new MultiCse();
            c.cses.add(singleCse.get(j));
            c.last_index = j;
            result.add(c);
        }
        for (int i = 0; i < result.size(); i++) {
            MultiCse frontMC = result.get(i);
            if (showMultiCse) {
                LOG.info(frontMC);
            }
            for (int index = frontMC.last_index + 1; index < singleCse.size(); index++) {
                SingleCse scA = singleCse.get(index);
                boolean ok = true;
                for (int k = 0; ok && k < frontMC.cses.size(); k++) {
                    SingleCse scB = frontMC.cses.get(k);
                    if (scB.hash == scA.hash || scB.conflict(scA) || !scB.contain(scA)) ok = false;
                }
                if (ok && result.size() < 5000) {
                    MultiCse newMC = new MultiCse();
                    newMC.cses = (ArrayList<SingleCse>) frontMC.cses.clone();
                    newMC.cses.add(scA);
                    newMC.last_index = index;
                    result.add(newMC);
                }
            }
        }
        long end = System.nanoTime();
        long totalTime = end - start;
        LOG.info("number of multi cse = " + result.size());
        LOG.info("bfs search all compatible plan cost " + (totalTime / 1e6) + "ms");
        return result;
    }

    private static Hop genAndSelectHop(ArrayList<MultiCse> multiCses, Hop template,Hop originalRoot) {
        // 构造出所有的Hop

        long constructHopTime = 0;
        long estimateCostTime = 0;
        long start, end;
        Hop result = originalRoot;
        double cost = estimate(originalRoot);

        for (int i = 0; i < multiCses.size(); i++) {
            start = System.nanoTime();
            MultiCse c = multiCses.get(i);
            Hop hop = createHop(c, template);
            end = System.nanoTime();
            constructHopTime += end - start;

            if (ec != null) {
                start = System.nanoTime();
                double newCost = estimate(hop);
                end = System.nanoTime();
                estimateCostTime += end - start;
                if (showCost)
                    LOG.info("cost=" + newCost);
                if (cost > newCost) {
                    result = hop;
                    cost = newCost;
                }
            }
        }

        LOG.info("construct hop time =" + (constructHopTime / 1e6) + "ms");
        LOG.info("estimate cost time =" + (estimateCostTime / 1e6) + "ms");
        LOG.info("minium cost = " + cost);
        if (showMinCostHop) {
            LOG.info(Explain.explain(result));
        }
        return result;
    }

    private static double estimate(Hop hop) {
        // 代价估计
        double cost = 1e30;
        try {
            Program programBlocks = constructProgramBlocks(hop);
            cost = CostEstimationWrapper.getTimeEstimate(programBlocks, ec);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return cost;
    }

    private static Hop createHop(MultiCse multiCse, Hop template) {
        Hop copy = deepCopyHopsDag(template);
        // 准备区间数组
        ArrayList<RangeTree> list = new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            SingleCse sc = new SingleCse(Range.of(i, i), leaves.get(i).hop);
            sc.name = getRangeName(i, i);
            RangeTree rangeTree = new RangeTree(i, i, sc);
            list.add(rangeTree);
        }
        for (SingleCse sc : multiCse.cses) {
            sc.prototype = null;
            for (Range range : sc.ranges) {
                list.add(new RangeTree(range.left, range.right, sc));
            }
        }
        for (Range r : blockRanges) {
            SingleCse sc = new SingleCse(Range.of(r.left, r.right), null);
            sc.name = getRangeName(r);
            list.add(new RangeTree(r.left, r.right, sc));
        }
        // 把区间数组排序，先按右端点升序，再按左端点降序
        list.sort((RangeTree ra, RangeTree rb) -> {
            if (ra.right != rb.right)
                return ra.right - rb.right;
            return rb.left - ra.left;
        });
        // 用栈建起RangeTree
        Stack<RangeTree> stack = new Stack<>();
        for (int i = 0; i < list.size(); i++) {
            RangeTree cur = list.get(i);
            while (!stack.empty() && stack.peek().left >= cur.left) {
                cur.children.add(stack.pop());
            }
            Collections.reverse(cur.children);
            stack.push(cur);
        }
        // 递归遍历RangeTree，建起HopDag
        for (RangeTree rt : stack) {
            // 建起一个块的HopDag
            Hop block_hop = rCreateHop(rt);
            // 找到这个块的位置
            Hop cur = copy;
            Hop parent = null;
            Leaf leaf1 = leaves.get(rt.left);
            Leaf leaf2 = leaves.get(rt.right);
            for (int i = 0; i < leaf1.depth && i < leaf2.depth; i++) {
                if (leaf1.path.get(i).equals(leaf2.path.get(i))) {
                    parent = cur;
                    assert cur != null;
                    cur = cur.getInput().get(leaf1.path.get(i));
                } else {
                    break;
                }
            }
            // 替换
            if (parent != null) {
                HopRewriteUtils.replaceChildReference(parent, cur, block_hop);
                HopRewriteUtils.cleanupUnreferenced(cur);
            } else {
                copy = block_hop;
            }
        }
     //   rewriteCommonSubexpressionElimination.rewriteHopDAG(copy,new ProgramRewriteStatus());
        return copy;
    }

    private static Hop rCreateHop(RangeTree rt) {
        if (rt.singleCse.prototype != null) {
            long hash1 = rangeHash1(rt.left, rt.right);
            long hash2 = rangeHash1(rt.singleCse.protoRange.left, rt.singleCse.protoRange.right);
            if (hash1 == hash2) {
                return rt.singleCse.prototype;
            } else {
                return HopRewriteUtils.createTranspose(rt.singleCse.prototype);
            }
        }
        if (rt.left == rt.right) {
            return leaves.get(rt.left).hop;
        } else {
            ArrayList<Hop> children = new ArrayList<>();
            for (RangeTree son : rt.children) {
                Hop s = rCreateHop(son);
                children.add(s);
            }
            Hop ret = build_binary_tree(children);
            rt.singleCse.prototype = ret;
            rt.singleCse.protoRange = Range.of(rt.left, rt.right);
            return ret;
        }
    }

    private static Hop build_binary_tree(ArrayList<Hop> mmChain) {
        assert mmChain.size() > 0;
        if (mmChain.size() == 1) return mmChain.get(0);
        ArrayList<Hop> mmOperators = new ArrayList<>();
        Hop ret = mmChain.get(0);
        for (int i = 1; i < mmChain.size(); i++) {
            Hop tmp = HopRewriteUtils.createMatrixMultiply(ret, mmChain.get(i));
            mmOperators.add(tmp);
            ret = tmp;
        }
        rewriteMatrixMultChainOptimization.optimizeMMChain(ret, mmChain, mmOperators, new ProgramRewriteStatus());
        return ret;
    }

    public static long rangeHash1(int l, int r) {
        long first = 0L;
        for (int i = 0; l + i <= r; i++) {
            long single;
            Hop h = leaves.get(l + i).hop;
            if (HopRewriteUtils.isTransposeOperation(h)) {
                h = h.getInput().get(0);
                if (AnalyzeSymmetryMatrix.querySymmetry(h.getName())) {
                    single = (long) h.getOpString().hashCode();
                } else {
                    single = (long) h.getOpString().hashCode() * 998244353;
                }
            } else {
                single = (long) h.getOpString().hashCode();
            }
            first = first + single * Prime.getPrime(i);
        }
        return first;
    }

    public static long rangeHash2(int l, int r) {
        long second = 0L;
        for (int i = 0; r - i >= l; i++) {
            long single;
            Hop h = leaves.get(r - i).hop;
            if (HopRewriteUtils.isTransposeOperation(h)) {
                h = h.getInput().get(0);
                single = (long) h.getOpString().hashCode();
            } else {
                if (AnalyzeSymmetryMatrix.querySymmetry(h.getName())) {
                    single = (long) h.getOpString().hashCode();
                } else {
                    single = (long) h.getOpString().hashCode() * 998244353;
                }
            }
            second = second + single * Prime.getPrime(i);
        }
        return second;
    }

    private static String getRangeName(Range range) {
        return getRangeName(range.left, range.right);
    }

    private static String getRangeName(int l, int r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (int i = l; i <= r; i++) {
            sb.append(MyExplain.myExplain(leaves.get(i).hop));
            sb.append(" ");
        }
        sb.append("}");
        return sb.toString();
    }


    public RewriteCoordinate(ExecutionContext executionContext) {
        ec = executionContext;
    }

    @Override
    public boolean createsSplitDag() {
        return false;
    }

    @Override
    public List<StatementBlock> rewriteStatementBlock(StatementBlock sb, ProgramRewriteStatus state) {
        try {
            LOG.info("Rewrite Coordinate StatementBlock");
            statementBlock = sb;
            if (sb.getHops() != null) {
                for (int i = 0; i < sb.getHops().size(); i++) {
                    Hop h = sb.getHops().get(i);
                    Hop g = rewiteHopDag(h);
                    sb.getHops().set(i, g);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        ArrayList<StatementBlock> list = new ArrayList<>();
        list.add(sb);
        return list;
    }

    @Override
    public List<StatementBlock> rewriteStatementBlocks(List<StatementBlock> sbs, ProgramRewriteStatus state) {
        return sbs;
    }

    private static Program constructProgramBlocks(Hop hop) {
        DMLProgram prog = new DMLProgram();
        StatementBlock sb = new StatementBlock();
        ArrayList<Hop> hops = new ArrayList<>();
        hops.add(hop);
        sb.setHops(hops);
        sb.setLiveIn(statementBlock.liveIn());
        sb.setLiveOut(statementBlock.liveOut());
        prog.addStatementBlock(sb);
        DMLTranslator dmlt = new DMLTranslator(prog);
        dmlt.constructLops(prog);
        Program rtprog = dmlt.getRuntimeProgram(prog, ConfigurationManager.getDMLConfig());
        return rtprog;
    }


}
