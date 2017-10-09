package cn.edu.nju.checker;

import cn.edu.nju.model.Context;
import cn.edu.nju.model.node.CCTNode;
import cn.edu.nju.model.node.STNode;
import cn.edu.nju.model.node.TreeNode;
import cn.edu.nju.util.BFunc;
import cn.edu.nju.util.LinkHelper;

import java.util.*;

/**
 * Created by njucjc on 2017/10/7.
 */
public class PccChecker extends Checker{

    private Map<String, STNode> stMap; //记录与context set相关的语法树结点

    private Map<String, List<CCTNode>> cctMap; //记录与context set相关的CCT关键结点


    public PccChecker(String name, STNode stRoot, Map<String, Set<Context>> contextSets, Map<String, STNode> stMap) {
        super(name, stRoot, contextSets);
        this.stMap = stMap;
        this.cctMap = new HashMap<>();

        //初始化
        for(String key : stMap.keySet()) {
            cctMap.put(key, new LinkedList<>());
        }

        //初始化CCT
        this.cctRoot = new CCTNode(stRoot.getNodeName(), stRoot.getNodeType());
        buildCCT(stRoot, this.cctRoot);
    }

    /**
     *
     * @param op: addition(+) or deletion(-)
     * @param contextSetName: the name of the changed context set
     * @param context: context
     */
    @Override
    public void update(String op, String contextSetName, Context context) {
        if(!affected(contextSetName)) {
            return ;
        }
        Set<Context> contextSet = contextSets.get(contextSetName);
        List<CCTNode> criticalNodeList = cctMap.get(contextSetName);
        STNode stNode = stMap.get(contextSetName);

        if("-".equals(op)) {
            contextSet.remove(context);
            for (CCTNode node : criticalNodeList) {
                //更新从关键节点到根节点的状态
                updateNodesToRoot(node);
                //删除相关子树
                List<TreeNode> childNodes= node.getChildTreeNodes();
                for (TreeNode n : childNodes) {
                    CCTNode child = (CCTNode)n;
                    if(context.equals(child.getContext())) {
                        removeCriticalNode((STNode) stRoot.getFirstChild(), child);
                        break;
                    }
                }

            }
        }
        else {
            contextSet.add(context);
            for (CCTNode node : criticalNodeList) {
                //更新从关键节点到根节点的状态
                updateNodesToRoot(node);
                //创建一个以context相关联的新子树
                CCTNode newChild = new CCTNode(stNode.getFirstChild().getNodeName(),((STNode)(stNode.getFirstChild())).getNodeType(), context);
                buildCCT((STNode) stNode.getFirstChild(), newChild);
                //添加到本结点
                node.addChildeNode(newChild);
            }
        }
    }

    /**
     *
     * @return violated link
     */
    @Override
    public String doCheck() {
        List<Context> param = new ArrayList<>();
        evaluation(cctRoot, param); //PCC计算
        if(cctRoot.getNodeValue()) {
            return "";
        }
        else {
            return  cctRoot.getLink();
        }
    }

    private boolean affected(String contextSetName) {
        return stMap.containsKey(contextSetName);
    }


    /**
     * 根据语法树构造CCT
     * @param stRoot
     * @param cctRoot
     */
    private void buildCCT(STNode stRoot, CCTNode cctRoot) {
        if (!stRoot.hasChildNodes()) {
            return ;
        }
        if(stRoot.getNodeType() == STNode.EXISTENTIAL_NODE || stRoot.getNodeType() == STNode.UNIVERSAL_NODE) {
            cctMap.get(stRoot.getContextSetName()).add(cctRoot); //add critical node information
            STNode stChild = (STNode) stRoot.getFirstChild();
            for(Context context : contextSets.get(stRoot.getContextSetName())) {
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

    /**
     * 从cctMap中删除cctRoot子树中相关的可能关键结点信息
     * @param stRoot
     * @param cctRoot
     */
    private void removeCriticalNode(STNode stRoot, CCTNode cctRoot) {
        if(stRoot.getNodeType() == STNode.UNIVERSAL_NODE || stRoot.getNodeType() == STNode.UNIVERSAL_NODE) {
            cctMap.get(stRoot.getContextSetName()).remove(cctRoot); //删除相关信息
            STNode stChild = (STNode) stRoot.getFirstChild();

            //全称量词和存在量词的子节点数由其相关的context set大小决定
            Set<Context> contextSet = contextSets.get(stRoot.getContextSetName());
            for(int i = 0; i < contextSet.size(); i++) {
                removeCriticalNode(stChild, (CCTNode) cctRoot.getChildTreeNodes().get(i));
            }
        }
        else {
            List<TreeNode> childNodes = stRoot.getChildTreeNodes();
            for (int i = 0; i < childNodes.size(); i++) {
                removeCriticalNode((STNode) childNodes.get(i), (CCTNode) cctRoot.getChildTreeNodes().get(i));
            }
        }

    }

    /**
     * 更新从关键结点到根结点路径上的所有结点状态信息
     * @param node
     */
    private void updateNodesToRoot(CCTNode node) {
        while(node != null) {
            node.setNodeStatus(CCTNode.PC_STATE); //更新为Partial checking
            node = (CCTNode) node.getParentTreeNode();
        }
    }

    /**
     * 根据结点状态来判定是否需要重新计算value和link
     * @param cctRoot
     * @param param
     * @return
     */
    private boolean  evaluation(CCTNode cctRoot, List<Context> param) {
        if(cctRoot.getNodeStatus() == CCTNode.NC_STATE) { //无需重算就直接返回
            return cctRoot.getNodeValue();
        }

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
                value = BFunc.bfun(cctRoot.getNodeName(), param.get(size - 1), param.get(size >= 2 ? size - 2:size - 1));
            }
            //设置本结点布尔值
            cctRoot.setNodeValue(value);
            String link = "";
            for(Context context : param) {
                link = link + context.toString() + ";";
            }
            //生成link
            cctRoot.setLink(link);
        }
        else {
            if(cctRoot.getNodeType() == CCTNode.NOT_NODE) {
                value = !evaluation((CCTNode) cctRoot.getFirstChild(), param);
                cctRoot.setNodeValue(value); //更新结点值
                cctRoot.setLink(((CCTNode) cctRoot.getFirstChild()).getLink()); //更新link信息
            }
            else if(cctRoot.getNodeType() == CCTNode.AND_NODE){
                CCTNode leftChild = (CCTNode) cctRoot.getChildTreeNodes().get(0);
                CCTNode rightChild = (CCTNode) cctRoot.getChildTreeNodes().get(1);
                boolean leftValue = evaluation(leftChild, param);
                boolean rightValue = evaluation(rightChild, param);

                value = leftValue && rightValue;
                cctRoot.setNodeValue(value); //更新结点值

                //更新link信息
                if(leftValue && !rightValue) {
                    cctRoot.setLink(rightChild.getLink());
                }
                else if(!leftValue && rightValue) {
                    cctRoot.setLink(leftChild.getLink());
                }
                else {
                    cctRoot.setLink(LinkHelper.linkCartesian(leftChild.getLink(), rightChild.getLink()));
                }
            }
            else if(cctRoot.getNodeType() == CCTNode.IMPLIES_NODE) {
                CCTNode leftChild = (CCTNode) cctRoot.getChildTreeNodes().get(0);
                CCTNode rightChild = (CCTNode) cctRoot.getChildTreeNodes().get(1);
                boolean leftValue = evaluation(leftChild, param);
                boolean rightValue = evaluation(rightChild, param);

                value =  !leftValue || (leftValue && rightValue);
                cctRoot.setNodeValue(value); //更新结点值
                //更新link信息
                if(value) {
                    cctRoot.setLink(LinkHelper.linkCartesian(leftChild.getLink(), rightChild.getLink()));
                }
                else {
                    cctRoot.setLink(rightChild.getLink());
                }
            }
            else if(cctRoot.getNodeType() == CCTNode.UNIVERSAL_NODE) {
                List<TreeNode> childNodes = cctRoot.getChildTreeNodes();

                StringBuilder satisfiedLink = new StringBuilder();
                StringBuilder violatedLink = new StringBuilder();

                value = true;
                for (TreeNode n : childNodes) {
                    CCTNode child = (CCTNode)n;
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
                cctRoot.setNodeValue(value); //更新结点值
                if (!value) {
                    violatedLink.deleteCharAt(violatedLink.length() -1);
                    cctRoot.setLink(violatedLink.toString());
                }
                else {
                    satisfiedLink.deleteCharAt(satisfiedLink.length() - 1);
                    cctRoot.setLink(satisfiedLink.toString());
                }
            }
            else  if(cctRoot.getNodeType() == CCTNode.EXISTENTIAL_NODE) {
                List<TreeNode> childNodes = cctRoot.getChildTreeNodes();

                StringBuilder satisfiedLink = new StringBuilder();
                StringBuilder violatedLink = new StringBuilder();

                value = false;
                for (TreeNode n : childNodes) {
                    CCTNode child = (CCTNode)n;
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
                cctRoot.setNodeValue(value);
                if (value) {
                    satisfiedLink.deleteCharAt(satisfiedLink.length() - 1);
                    cctRoot.setLink(satisfiedLink.toString());
                }
                else {
                    violatedLink.deleteCharAt(violatedLink.length() -1);
                    cctRoot.setLink(violatedLink.toString());
                }
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

}
