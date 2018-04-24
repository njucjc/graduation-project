package cn.edu.nju.checker;

import static jcuda.driver.JCudaDriver.*;

import cn.edu.nju.context.Context;
import cn.edu.nju.memory.Config;
import cn.edu.nju.memory.GPUContextMemory;
import cn.edu.nju.memory.GPUPatternMemory;
import cn.edu.nju.memory.GPURuleMemory;
import cn.edu.nju.node.NodeType;
import cn.edu.nju.node.STNode;
import cn.edu.nju.pattern.Pattern;
import cn.edu.nju.util.LogFileHelper;
import jcuda.Pointer;
import jcuda.Sizeof;
import jcuda.driver.CUdeviceptr;
import jcuda.runtime.dim3;
import jcuda.utils.KernelLauncher;

import java.util.*;

public class GAINChecker extends Checker {

    private int stSize;

    private int [] branchSize;

    private STNode [] constraintNodes; //(an array for storing a reordered syntax tree)

    private List<Integer> cunits; //an array for storing the start of each c-unit

    private GPURuleMemory gpuRuleMemory;

    private KernelLauncher genTruthValue;

    private KernelLauncher genLinks;

    private KernelLauncher updatePattern;

    private GPUContextMemory gpuContextMemory;

    private GPUPatternMemory gpuPatternMemory;

    private Map<String, Integer> patternIdMap;

    private static final int threadPerBlock = 32;

    public GAINChecker(String name, STNode stRoot, Map<String, Pattern> patternMap, Map<String, STNode> stMap,
                       KernelLauncher genTruthValue, KernelLauncher genLinks, KernelLauncher updatePattern,
                       List<String> contexts) {
        super(name, stRoot, patternMap, stMap);
        this.stSize = computeSTSize(stRoot);
 //       System.out.println(name + ": " + stSize);

        this.branchSize = new int[stSize];

        //计算cunit以及为语法树重排序(前序遍历)
        this.constraintNodes = new STNode[stSize];
        this.cunits = new ArrayList<>();
        split(stRoot);
        cunits.add(-1);
        //将语法树信息拷贝到GPU

        this.genTruthValue = genTruthValue;
        this.genLinks = genLinks;
        this.updatePattern = updatePattern;

        this.gpuContextMemory = GPUContextMemory.getInstance(contexts);
        this.gpuPatternMemory = GPUPatternMemory.getInstance(patternMap.keySet());

        this.patternIdMap = gpuPatternMemory.getIndexMap();
        initGPURuleMemory();
    }


    private void initGPURuleMemory() {
        int [] parent = new int[stSize];
        int [] leftChild = new int[stSize];
        int [] rightChild = new int[stSize];
        int [] nodeType = new int[stSize];
        int [] patternId = new int[stSize];

        for(int i = 0; i < stSize; i++) {
            STNode p = (STNode) constraintNodes[i].getParentTreeNode();
            STNode l = (STNode) constraintNodes[i].getFirstChild();
            STNode r = (STNode) constraintNodes[i].getLastChild();

            parent[i] = p != null ? p.getNodeNum() : -1;
            leftChild[i] = l != null ? l.getNodeNum() : -1;
            rightChild[i] = r != null ? r.getNodeNum() : -1;
            patternId[i] = -1;

            int type = constraintNodes[i].getNodeType();
            if(type != NodeType.BFUNC_NODE) {
                nodeType[i] = type;
                if(type == NodeType.UNIVERSAL_NODE || type == NodeType.EXISTENTIAL_NODE) {
                    patternId[i] = patternIdMap.get(constraintNodes[i].getContextSetName());
                }
            }
            else {
                String name = constraintNodes[i].getNodeName();
                if("sz_loc_range".equals(name)) {
                    nodeType[i] = NodeType.SZ_LOC_RANGE;
                }
                else if("same".equals(name)) {
                    nodeType[i] = NodeType.SAME;
                }
                else if("sz_loc_close".equals(name)) {
                    nodeType[i] = NodeType.SZ_LOC_CLOSE;
                }
                else if("sz_spd_close".equals(name)) {
                    nodeType[i] = NodeType.SZ_SPD_CLOSE;
                }
                else if("sz_loc_dist".equals(name)) {
                    nodeType[i] = NodeType.SZ_LOC_DIST;
                }
                else if("sz_loc_dist_neq".equals(name)) {
                    nodeType[i] = NodeType.SZ_LOC_DIST_NEQ;
                }
                else {
                    assert false:"BFunc type error.";
                }
            }

            this.gpuRuleMemory = new GPURuleMemory(stSize, parent, leftChild, rightChild, nodeType, patternId);
        }
    }



    @Override
    public boolean doCheck() {
       // assert false:"Something is being to do.";
        computeRTTBranchSize(this.stRoot);
        int cctSize = branchSize[stSize - 1];

        CUdeviceptr deviceTruthValue = new CUdeviceptr();
        cuMemAlloc(deviceTruthValue, cctSize * Sizeof.SHORT);

        CUdeviceptr deviceBranchSize = new CUdeviceptr();
        cuMemAlloc(deviceBranchSize, stSize * Sizeof.INT);
        cuMemcpyHtoD(deviceBranchSize, Pointer.to(branchSize), stSize * Sizeof.INT);


        CUdeviceptr deviceLinks = new CUdeviceptr();
        cuMemAlloc(deviceLinks, (1 + Config.MAX_PARAN_NUM * Config.MAX_LINK_SIZE) * Sizeof.INT * cctSize);

        CUdeviceptr deviceLinkResult = new CUdeviceptr();
        cuMemAlloc(deviceLinkResult, (Config.MAX_PARAN_NUM * Config.MAX_LINK_SIZE) * Sizeof.INT);

        CUdeviceptr deviceLinkNum = new CUdeviceptr();
        cuMemAlloc(deviceLinkNum, Sizeof.INT);

        for(int i = cunits.size() - 2; i >= 0; i--) {
            int ccopyNum = computeCCopyNum(cunits.get(i));

            dim3 gridSize = new dim3(threadPerBlock, 1, 1);
            dim3 blockSize = new dim3((ccopyNum + threadPerBlock - 1) / threadPerBlock,1, 1);

            genTruthValue.setup(gridSize, blockSize)
                    .call(gpuRuleMemory.getParent(), gpuRuleMemory.getLeftChild(), gpuRuleMemory.getRightChild(), gpuRuleMemory.getNodeType(), gpuRuleMemory.getPatternId(),
                            deviceBranchSize, cunits.get(i + 1) + 1, cunits.get(i),
                            gpuPatternMemory.getBegin(), gpuPatternMemory.getLength(), gpuPatternMemory.getContexts(),
                             gpuContextMemory.getLongitude(), gpuContextMemory.getLatitude(), gpuContextMemory.getSpeed(),
                            deviceTruthValue,
                            ccopyNum);


            genLinks.setup(gridSize, blockSize)
                    .call(gpuRuleMemory.getParent(), gpuRuleMemory.getLeftChild(), gpuRuleMemory.getRightChild(), gpuRuleMemory.getNodeType(), gpuRuleMemory.getPatternId(),
                            deviceBranchSize, cunits.get(i + 1) + 1, cunits.get(i),
                            gpuPatternMemory.getBegin(), gpuPatternMemory.getLength(), gpuPatternMemory.getContexts(),
                            gpuContextMemory.getLongitude(), gpuContextMemory.getLatitude(), gpuContextMemory.getSpeed(),
                            deviceTruthValue,
                            deviceLinks, deviceLinkResult, deviceLinkNum,
                            cunits.get(0),
                            ccopyNum);

        }

        short [] hostTruthValue = new short[cctSize];
        cuMemcpyDtoH(Pointer.to(hostTruthValue), deviceTruthValue, cctSize * Sizeof.SHORT);

        boolean value = hostTruthValue[cctSize - 1] == 1;

        int [] hostLinkResult = new int[Config.MAX_PARAN_NUM * Config.MAX_LINK_SIZE];
        cuMemcpyDtoH(Pointer.to(hostLinkResult), deviceLinkResult, (Config.MAX_PARAN_NUM * Config.MAX_LINK_SIZE) * Sizeof.INT);

        int [] hostLinkNum = new int[1];
        cuMemcpyDtoH(Pointer.to(hostLinkNum), deviceLinkNum, Sizeof.INT);

        if(!value) {
            parseLink(hostLinkResult, hostLinkNum[0]);
        }

        cuMemFree(deviceTruthValue);
        cuMemFree(deviceBranchSize);
        cuMemFree(deviceLinks);
        cuMemFree(deviceLinkResult);
        cuMemFree(deviceLinkNum);


        return value;
    }

    private void parseLink(int [] links, int size) {
        for(int i = 0; i < size; i++) {
            String link = "";
            for(int j = 0; j < Config.MAX_PARAN_NUM; j++) {
                if(links[i + j] != -1) {
                    link = link + "ctx_" + links[i + j] + " ";
                }
            }
            LogFileHelper.getLogger().info(getName() + " " + link);
            addIncLink(link);
            //link = link.substring(0, link.length() - 1);
        }
    }

    @Override
    public synchronized boolean add(String patternId, Context context) {
      //  return super.add(patternId, context);
        if (!addContextToPattern(patternId, context)) {
            return false;
        }
        //assert false:"Something is being to do.";
        CUdeviceptr op = new CUdeviceptr();
        cuMemAlloc(op, Sizeof.INT);
        cuMemcpyHtoD(op, Pointer.to(new int[]{1}), Sizeof.INT);

        CUdeviceptr pattern_idx = new CUdeviceptr();
        cuMemAlloc(pattern_idx, Sizeof.INT);
        cuMemcpyHtoD(pattern_idx, Pointer.to(new int[]{patternIdMap.get(patternId)}), Sizeof.INT);

        CUdeviceptr id = new CUdeviceptr();
        cuMemAlloc(id, Sizeof.INT);
        cuMemcpyHtoD(id, Pointer.to(new int[]{context.getId()}), Sizeof.INT);

        updatePattern.setup(new dim3(1,1,1), new dim3(1,1,1))
                   .call(op, pattern_idx,
                        gpuPatternMemory.getBegin(), gpuPatternMemory.getLength(), gpuPatternMemory.getContexts(),
                        id);

        cuMemFree(op);
        cuMemFree(pattern_idx);
        cuMemFree(id);
        return true;
    }

    @Override
    public synchronized boolean delete(String patternId, String timestamp) {
        if(!deleteContextFromPattern(patternId, timestamp)) {
            return false;
        }
       // assert false:"Something is being to do.";
        CUdeviceptr op = new CUdeviceptr();
        cuMemAlloc(op, Sizeof.INT);
        cuMemcpyHtoD(op, Pointer.to(new int[]{0}), Sizeof.INT); //delete

        CUdeviceptr pattern_idx = new CUdeviceptr();
        cuMemAlloc(pattern_idx, Sizeof.INT);
        cuMemcpyHtoD(pattern_idx, Pointer.to(new int[]{patternIdMap.get(patternId)}), Sizeof.INT);

        CUdeviceptr id = new CUdeviceptr();
        cuMemAlloc(id, Sizeof.INT);
        cuMemcpyHtoD(id, Pointer.to(new int[]{0}), Sizeof.INT);

        updatePattern.setup(new dim3(1,1,1), new dim3(1,1,1))
                .call(op, pattern_idx,
                        gpuPatternMemory.getBegin(), gpuPatternMemory.getLength(), gpuPatternMemory.getContexts(),
                        id);

        cuMemFree(op);
        cuMemFree(pattern_idx);
        cuMemFree(id);
        return true;
    }

    private int computeSTSize(STNode root) {
        if(root == null) {
            return 0;
        }
        int type = root.getNodeType();
        if(type == STNode.UNIVERSAL_NODE || type == NodeType.EXISTENTIAL_NODE || type == STNode.NOT_NODE) {
            return 1 + computeSTSize((STNode) root.getFirstChild());
        }
        else if(type == STNode.AND_NODE || type == STNode.IMPLIES_NODE) { //not support 'OR' node type
            return 1 + computeSTSize((STNode) root.getFirstChild()) + computeSTSize((STNode) root.getLastChild());
        }
        else if(type == STNode.BFUNC_NODE) {
            return 1;
        }
        else {
            assert false:"Node type error, type:  " +  type;
            return 0;
        }

    }

    private void split(STNode root) {
        Queue<STNode> rootOfCunit = new LinkedList<>();
        rootOfCunit.offer(root);
        int [] currentNodeNum = new int[1];
        currentNodeNum[0] = this.stSize - 1;
        while(!rootOfCunit.isEmpty()) {
            STNode rootOfNextCunit = rootOfCunit.poll();
            cunits.add(currentNodeNum[0]);
            parseCunit(rootOfCunit, rootOfNextCunit, currentNodeNum);
        }
    }

    private void parseCunit(Queue<STNode> rootOfCunit, STNode node,int []currentNodeNum) {
        //假定非全称/存在量词的子树中不会有全称/存在量词节点
        if(node == null) {
            return ;
        }
  //      System.out.println(currentNodeNum[0] + " :" + node.getNodeName());
        node.setNodeNum(currentNodeNum[0]);
        constraintNodes[currentNodeNum[0]] = node;
        currentNodeNum[0]--;
        int type = node.getNodeType();
        if (type == STNode.UNIVERSAL_NODE || type == STNode.EXISTENTIAL_NODE) {
            rootOfCunit.offer((STNode) node.getFirstChild());
        }
        else if(type == STNode.IMPLIES_NODE || type == STNode.AND_NODE){
            parseCunit(rootOfCunit, (STNode) node.getLastChild(), currentNodeNum);
            parseCunit(rootOfCunit, (STNode) node.getFirstChild(), currentNodeNum);
        }
        else if(type == STNode.NOT_NODE) {
            parseCunit(rootOfCunit, (STNode) node.getFirstChild(), currentNodeNum);
        }
        else if(type == STNode.BFUNC_NODE) {
            return;
        }
    }

    private int computeCCopyNum(int cunit) {
        STNode node = constraintNodes[cunit];
        int ccopyNum = 1;
        while(node != null) {
            int type = node.getNodeType();
            if(type == STNode.UNIVERSAL_NODE || type == STNode.EXISTENTIAL_NODE) {
                ccopyNum *= patternMap.get(node.getContextSetName()).getContextList().size();
            }
            node = (STNode) node.getParentTreeNode();
        }
        return ccopyNum;
    }

    private int computeRTTBranchSize(STNode root) {
        assert root != null:"root is null.";
        int type = root.getNodeType();
        int size = 0;
        if(type == STNode.UNIVERSAL_NODE || type == STNode.EXISTENTIAL_NODE) {
            size = 1 + patternMap.get(root.getContextSetName()).getContextList().size() * computeRTTBranchSize((STNode) root.getFirstChild());
        }
        else if(type == STNode.NOT_NODE) {
            size = 1 + computeRTTBranchSize((STNode)root.getFirstChild());
        }
        else if(type == STNode.AND_NODE || type == STNode.IMPLIES_NODE) {
            size = 1 + computeRTTBranchSize((STNode)root.getFirstChild()) + computeRTTBranchSize((STNode)root.getLastChild());
        }
        else if(type == STNode.BFUNC_NODE){
            size = 1;
        }
        else {
            assert false:"Type error.";
        }
        branchSize[root.getNodeNum()] = size;
        return size;
    }

    @Override
    public void reset() {
        this.gpuRuleMemory.free();
        this.gpuPatternMemory.free();
        this.gpuContextMemory.free();
        super.reset();
    }
}
