package com.insight.agent.attribution;

import com.insight.agent.attribution.AttributionPorts.DimensionProposer;
import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import java.util.List;

/**
 * 不依赖模型的提议器：按固定顺序试还没试过的维度。
 *
 * <p>它有两个用途：模型不可用时降级（总比整个功能不可用强），
 * 以及在没有模型的阶段把链路跑通。顺序是刻意的——先品类再区域最后门店：
 * 品类基数适中、一次就能落到可行动的结论；门店基数太大，先拆门店容易直接得到一堆碎片。
 *
 * <p>问题里已经钉死的维度不再提议：问"华东为什么跌"时根范围已经是华东，
 * 再按区域拆只会得到一个分组，白烧一次查询。
 */
public class RuleBasedProposer implements DimensionProposer {

    private static final List<Dimension> ORDER = List.of(Dimension.CATEGORY, Dimension.REGION, Dimension.SHOP);

    private final Scope rootScope;

    public RuleBasedProposer(Scope rootScope) {
        this.rootScope = rootScope;
    }

    @Override
    public Dimension propose(ProposalContext context) {
        for (Dimension dimension : ORDER) {
            if (context.used().contains(dimension) || context.exhausted().contains(dimension)
                    || rootScope.contains(dimension)) {
                continue;
            }
            return dimension;
        }
        return null;
    }
}
