package org.apache.sysds.hops.rewrite.dfp.utils;

import org.apache.sysds.common.Types;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;

import static org.apache.sysds.hops.rewrite.dfp.utils.Judge.*;

public class MyExplain {

//    public static void myResetVisitStatus(Hop hop) {
//        hop.setVisited(false);
//        for (int i = 0; i < hop.getInput().size(); i++) {
//            myResetVisitStatus(hop.getInput().get(i));
//        }
//    }

    public static String myExplain(Hop hop) {
        String ans = explain_iter(hop);
        return ans;
    }

    private static String explain_iter(Hop hop) {
        StringBuilder sb = new StringBuilder();
        if (isRead(hop)) {
            // Tread
            sb.append(((DataOp) hop).getName());
        } else if (isWrite(hop)) {
            // Twrite
            sb.append(((DataOp) hop).getName());
            sb.append(":=");
            sb.append( explain_iter(hop.getInput().get(0)));
        } else if (isLeafMatrix(hop)) {
            // 叶节点上的矩阵
            sb.append(hop.getName());
        } else if (HopRewriteUtils.isMatrixMultiply(hop)) {
            // 矩阵乘法
            sb.append("(");
            sb.append(explain_iter(hop.getInput().get(0)));
            sb.append(" %*% ");
            sb.append(explain_iter(hop.getInput().get(1)));
            sb.append(")");
        } else if (HopRewriteUtils.isTransposeOperation(hop)) {
            // 转置
            sb.append("t(");
            sb.append(explain_iter(hop.getInput().get(0)));
            sb.append(")");
        } else if (hop instanceof BinaryOp) {
            String op = ((BinaryOp) hop).getOp().toString();
            //System.out.println(op);
            sb.append("(");
            sb.append(explain_iter(hop.getInput().get(0)));
            sb.append(" "+op+" ");
            sb.append(explain_iter(hop.getInput().get(1)));
            sb.append(")");
        } else {
            sb.append("{");
            sb.append(hop.getOpString());
            for (int i = 0; i < hop.getInput().size(); i++) {
                sb.append(",");
                sb.append(explain_iter(hop.getInput().get(i)));
            }
            sb.append("}");
        }
        return sb.toString();
    }

}
