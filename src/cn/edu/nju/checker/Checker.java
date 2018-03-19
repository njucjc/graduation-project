package cn.edu.nju.checker;

import cn.edu.nju.context.Context;
import cn.edu.nju.node.CCTNode;
import cn.edu.nju.node.STNode;
import cn.edu.nju.node.TreeNode;
import cn.edu.nju.pattern.Pattern;
import cn.edu.nju.util.BFuncHelper;
import cn.edu.nju.util.LinkHelper;
import cn.edu.nju.util.TimestampHelper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by njucjc on 2017/10/3.
 */
public abstract class Checker {
    /* Checker(rule) name*/
    protected String name;

    /* CCT root for constraint */
    protected CCTNode cctRoot;

    /*Syntax tree for constraint*/
    protected STNode stRoot;

    protected Map<String, Pattern> patternMap;

    private Set<String> incLinkSet;

    protected int checkTimes = 0;

    protected long timeCount = 0L;

    protected Map<String, STNode> stMap; //记录与context set相关的语法树结点

    private Map<String, List<CCTNode>> cctMap; //记录与context set相关的CCT关键结点


    public Checker(String name, STNode stRoot, Map<String, Pattern> patternMap, Map<String, STNode> stMap) {
        this.name = name;
        this.stRoot = stRoot;
        this.patternMap = patternMap;
        this.incLinkSet = ConcurrentHashMap.newKeySet();
        this.checkTimes = 0;

        this.stMap = stMap;
        this.cctMap = new ConcurrentHashMap<>();

        //初始化
        for(String key : stMap.keySet()) {
            cctMap.put(key, new CopyOnWriteArrayList<>());
        }

        //初始化CCT
        this.cctRoot = new CCTNode(stRoot.getNodeName(), stRoot.getNodeType());
        buildCCT(stRoot, this.cctRoot);
    }

    protected Checker() {}

    public synchronized String getName() {
        return name;
    }

    public synchronized void setName(String name) {
        this.name = name;
    }

    public synchronized int getInc() {
        return incLinkSet.size();
    }

    protected synchronized boolean addIncLink(String link) {
        return incLinkSet.add(link);
    }

    protected synchronized Set<String> getIncLinkSet() {
        return incLinkSet;
    }

    /**
     * 做一次一致性检测
     * @return violated link
     */
    abstract public boolean doCheck();

    /**
     *
     * @param patternId
     * @param context
     * @return
     */
    public synchronized boolean add(String patternId, Context context) {
        if (!affected(patternId)) {
            return false;
        }
        List<CCTNode> criticalNodeList = cctMap.get(patternId);
        STNode stNode = stMap.get(patternId);
        Pattern pattern = patternMap.get(patternId);
        assert stNode.getNodeType() == STNode.EXISTENTIAL_NODE
                || stNode.getNodeType() == STNode.UNIVERSAL_NODE
                :"[DEBUG] Type Error.";
        for (CCTNode node : criticalNodeList) {
            //更新从关键节点到根节点的状态
            updateNodesToRoot(node);
            //创建一个以context相关联的新子树
            CCTNode newChild = new CCTNode(stNode.getFirstChild().getNodeName(),((STNode)(stNode.getFirstChild())).getNodeType(), context);
            buildCCT((STNode) stNode.getFirstChild(), newChild);
            //添加到本结点
            node.addChildeNode(newChild);
        }
//        System.out.println("[Check add]: " + context);
        return true;
    }


    public synchronized boolean delete(String patternId, String timestamp) {
        if (!affected(patternId)) {
            return false;
        }
        List<CCTNode> criticalNodeList = cctMap.get(patternId);
        STNode stNode = stMap.get(patternId);
        Pattern pattern = patternMap.get(patternId);
        assert stNode.getNodeType() == STNode.EXISTENTIAL_NODE
                || stNode.getNodeType() == STNode.UNIVERSAL_NODE
                :"[DEBUG] Type Error.";

        for (CCTNode node : criticalNodeList) {
            //更新从关键节点到根节点的状态
            updateNodesToRoot(node);

            //删除timestamp时刻过期结点
            List<TreeNode> childTreeNodes = node.getChildTreeNodes();
            for (TreeNode treeNode : childTreeNodes) {//是否存在结点
                CCTNode child = (CCTNode)treeNode; //第一个结点
                if (TimestampHelper.timestampDiff(child.getContext().getTimestamp(), timestamp) >= pattern.getFreshness()) {
                    removeCriticalNode((STNode) stNode.getFirstChild(), child);
                    childTreeNodes.remove(treeNode);
//                    System.out.println("[Check delete]: " + child.getContext());
                }
                else {
                    break;
                }
            }
        }
        return true;
    }



    /**
     * 根据语法树构造CCT
     * @param stRoot
     * @param cctRoot
     */
    protected synchronized void buildCCT(STNode stRoot, CCTNode cctRoot) {
        if (!stRoot.hasChildNodes()) {
            return ;
        }
        if(stRoot.getNodeType() == STNode.EXISTENTIAL_NODE || stRoot.getNodeType() == STNode.UNIVERSAL_NODE) {
            cctMap.get(stRoot.getContextSetName()).add(cctRoot); //add critical node information
            STNode stChild = (STNode) stRoot.getFirstChild();
            for(Context context : patternMap.get(stRoot.getContextSetName()).getContextList()) {
                //CCT结点创建默认为FC状态
                CCTNode cctChild = new CCTNode(stChild.getNodeName(), stChild.getNodeType(), context);
                buildCCT(stChild, cctChild);
                cctRoot.addChildeNode(cctChild);
            }
        }
        else {
            List<TreeNode> childNodes = stRoot.getChildTreeNodes();
            for (TreeNode n : childNodes) {
                STNode stChild = (STNode) n;
                CCTNode cctChild = new CCTNode(stChild.getNodeName(), stChild.getNodeType());
                buildCCT(stChild, cctChild);
                cctRoot.addChildeNode(cctChild);
            }
        }
    }


    private synchronized boolean affected(String contextSetName) {
        return stMap.containsKey(contextSetName);
    }


    /**
     * 从cctMap中删除cctRoot子树中相关的可能关键结点信息
     * @param stRoot
     * @param cctRoot
     */
    protected synchronized void removeCriticalNode(STNode stRoot, CCTNode cctRoot) {
        assert stRoot.getNodeType() == cctRoot.getNodeType()
                :"[DEBUG] Type error:" + stRoot.getNodeName() + " != " + cctRoot.getNodeName();
        if(!cctRoot.hasChildNodes()) {
            return;
        }
        if(stRoot.getNodeType() == STNode.UNIVERSAL_NODE || stRoot.getNodeType() == STNode.EXISTENTIAL_NODE) {
            cctMap.get(stRoot.getContextSetName()).remove(cctRoot);//删除相关信息
            STNode stChild = (STNode) stRoot.getFirstChild();

            //全称量词和存在量词的子节点数由其相关的context set大小决定
  //          List<Context> contextSet = patternMap.get(stRoot.getContextSetName()).getContextList();
 //           assert contextSet.size() == cctRoot.getChildTreeNodes().size():"[DEBUG] Size error." + stRoot.getContextSetName();
            for(int i = 0; i < cctRoot.getChildTreeNodes().size(); i++) {
                removeCriticalNode(stChild, (CCTNode) cctRoot.getChildTreeNodes().get(i));
            }
        }
        else {
            List<TreeNode> childNodes = stRoot.getChildTreeNodes();
            assert childNodes.size() == cctRoot.getChildTreeNodes().size():"[DEBUG] Size error.";
            for (int i = 0; i < childNodes.size(); i++) {
                removeCriticalNode((STNode) childNodes.get(i), (CCTNode) cctRoot.getChildTreeNodes().get(i));
            }
        }

    }


    /**
     * 更新从关键结点到根结点路径上的所有结点状态信息
     * @param node
     */
    private synchronized void updateNodesToRoot(CCTNode node) {
        while(node != null) {
            node.setNodeStatus(CCTNode.PC_STATE); //更新为Partial checking
            node = (CCTNode) node.getParentTreeNode();
        }
    }

    /**
     * CCT求值函数，虽然结点带有状态，但是是最简单的实现，没用利用结点的历史状态信息
     * @param cctRoot
     * @param param
     * @return
     */
    protected synchronized boolean evaluation(CCTNode cctRoot, List<Context> param) {
        if(cctRoot.getContext() != null) {
            param.add(cctRoot.getContext());
        }

        boolean value = false;
        if(!cctRoot.hasChildNodes()) {//叶子结点只可能是全称量词、存在量词或bfunc
            if(cctRoot.getNodeType() == CCTNode.UNIVERSAL_NODE) {
                value = true;
            }
            else if(cctRoot.getNodeType() == CCTNode.EXISTENTIAL_NODE) {
                value = false;
            }
            else {
                int size = param.size();
                assert size >= 1:"[DEBUG] Param error";
                value = BFuncHelper.bfun(cctRoot.getNodeName(), param.get(size - 1), param.get(size >= 2 ? size - 2:size - 1));
            }
            //设置本结点布尔值
            cctRoot.setNodeValue(value);
            String link = "";
            for(Context context : param) {
                link = link + context.toString() + " ";
            }
            if(!"".equals(link)) {
                link = link.substring(0, link.length() - 1);
            }
            //生成link
            cctRoot.setLink(link);
        }
        else {
            if(cctRoot.getNodeType() == CCTNode.NOT_NODE) {
                value = notNodeEval(cctRoot, param);
            }
            else if(cctRoot.getNodeType() == CCTNode.AND_NODE){
                value = andNodeEval(cctRoot, param);
            }
            else if(cctRoot.getNodeType() == CCTNode.IMPLIES_NODE) {
                value = impliesNodeEval(cctRoot, param);
            }
            else if(cctRoot.getNodeType() == CCTNode.UNIVERSAL_NODE) {
                value = universalNodeEval(cctRoot, param, 0, cctRoot.getChildTreeNodes().size() - 1).getValue();
            }
            else  if(cctRoot.getNodeType() == CCTNode.EXISTENTIAL_NODE) {

                value = existentialNodeEval(cctRoot, param,0, cctRoot.getChildTreeNodes().size() - 1).getValue();
            }
            else {
                assert false:"[DEBUG] Illegal CCT node: " + cctRoot.getNodeName() + ".";
            }
        }

        //本结点计算完毕就将结点状态更新为NC（无需重算状态）
        cctRoot.setNodeStatus(CCTNode.NC_STATE);
        //返回上一层
        if (cctRoot.getContext() != null) {
            param.remove(param.size() - 1);
        }

        return value;
    }


    protected synchronized boolean notNodeEval(CCTNode notNode, List<Context> param) {
        boolean value = !evaluation((CCTNode) notNode.getFirstChild(), param);
        notNode.setNodeValue(value); //更新结点值
        notNode.setLink(((CCTNode) notNode.getFirstChild()).getLink()); //更新link信息
        return value;
    }


    protected synchronized boolean andNodeEval(CCTNode andNode, List<Context> param) {

        CCTNode leftChild = (CCTNode) andNode.getChildTreeNodes().get(0);
        CCTNode rightChild = (CCTNode) andNode.getChildTreeNodes().get(1);
        boolean leftValue = evaluation(leftChild, param);
        boolean rightValue = evaluation(rightChild, param);

        boolean value = leftValue && rightValue;
        andNode.setNodeValue(value); //更新结点值

        //更新link信息
        if(leftValue && !rightValue) {
            andNode.setLink(rightChild.getLink());
        }
        else if(!leftValue && rightValue) {
            andNode.setLink(leftChild.getLink());
        }
        else {
            andNode.setLink(LinkHelper.linkCartesian(leftChild.getLink(), rightChild.getLink()));
        }

        return value;
    }


    protected synchronized boolean impliesNodeEval(CCTNode impliesNode, List<Context> param) {
        CCTNode leftChild = (CCTNode) impliesNode.getChildTreeNodes().get(0);
        CCTNode rightChild = (CCTNode) impliesNode.getChildTreeNodes().get(1);
        boolean leftValue = evaluation(leftChild, param);
        boolean rightValue = evaluation(rightChild, param);

        boolean value =  !leftValue || (leftValue && rightValue);
        impliesNode.setNodeValue(value); //更新结点值
        //更新link信息
        if(value) {
            impliesNode.setLink(LinkHelper.linkCartesian(leftChild.getLink(), rightChild.getLink()));
        }
        else {
            impliesNode.setLink(rightChild.getLink());
        }

        return value;
    }


    protected synchronized Result universalNodeEval(CCTNode universalNode, List<Context> param,int start, int end) {
        List<TreeNode> childNodes = universalNode.getChildTreeNodes();

        StringBuilder satisfiedLink = new StringBuilder();
        StringBuilder violatedLink = new StringBuilder();

        boolean value = true;
        for (int i = start; i <= end; i++) {
            CCTNode child = (CCTNode)childNodes.get(i);
            boolean b = evaluation(child, param);
            value = value && b;
            if (b) {
                if(value) {
                    satisfiedLink.append(child.getLink());
                    satisfiedLink.append("#");
                }
            }
            else {
                violatedLink.append(child.getLink());
                violatedLink.append("#");
            }

        }
        universalNode.setNodeValue(value); //更新结点值
        String link;
        if (!value) {
            violatedLink.deleteCharAt(violatedLink.length() -1);
            link = violatedLink.toString();
        }
        else {
            satisfiedLink.deleteCharAt(satisfiedLink.length() - 1);
            link = satisfiedLink.toString();
        }
        universalNode.setLink(link);
        return new Result(value,link);
    }

    protected synchronized Result existentialNodeEval(CCTNode existentialNode, List<Context> param, int start, int end) {
        List<TreeNode> childNodes = existentialNode.getChildTreeNodes();

        StringBuilder satisfiedLink = new StringBuilder();
        StringBuilder violatedLink = new StringBuilder();

        boolean value = false;
        for (int i = start; i <= end; i++) {
            CCTNode child = (CCTNode)childNodes.get(i);
            boolean b = evaluation(child, param);
            value = value || b;
            if (b) {
                satisfiedLink.append(child.getLink());
                satisfiedLink.append("#");
            }
            else {
                if(!value) {
                    violatedLink.append(child.getLink());
                    violatedLink.append("#");
                }
            }
        }
        existentialNode.setNodeValue(value);
        String link;
        if (value) {
            satisfiedLink.deleteCharAt(satisfiedLink.length() - 1);
            link = satisfiedLink.toString();

        }
        else {
            violatedLink.deleteCharAt(violatedLink.length() -1);
            link = violatedLink.toString();
        }
        existentialNode.setLink(link);
        return new Result(value, link);
    }


    public void printSyntaxTree() {
        BFSOrder(stRoot);
    }

    public void printCCT() {
        BFSOrder(cctRoot);
    }

    private void BFSOrder(TreeNode root) {
        if (root == null) {
            return ;
        }
        Queue<TreeNode> queue = new LinkedList<>();
        queue.add(root);
        TreeNode last = root;
        TreeNode nlast = root;
        while (!queue.isEmpty()) {
            TreeNode t = queue.poll();
            System.out.print(t.getNodeName());
            if(t instanceof CCTNode) {
                Context c = ((CCTNode) t).getContext();
                if(c != null) {
                    System.out.print("(" + c.getPlateNumber() + ") ");
                }
                else {
                    System.out.print(" ");
                }
            }
            else {
                System.out.print(" ");
            }

            for(TreeNode n : t.getChildTreeNodes()) {
                queue.add(n);
                nlast = n;
            }

            if(t == last) {
                System.out.println();
                last = nlast;
            }
        }

    }

    protected synchronized void clearCCTMap() {
        for (String key : cctMap.keySet()) {
           cctMap.get(key).clear();
        }
    }

    public int getCheckTimes() {
        return checkTimes;
    }

    public long getTimeCount() {
        return timeCount;
    }

}
