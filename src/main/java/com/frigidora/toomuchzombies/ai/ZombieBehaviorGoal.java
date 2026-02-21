package com.frigidora.toomuchzombies.ai;

import java.util.EnumSet;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Zombie;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.frigidora.toomuchzombies.TooMuchZombies;

public class ZombieBehaviorGoal implements Goal<Zombie> {

    private final Zombie zombie;
    private final ZombieAgent agent;
    private final GoalKey<Zombie> key;

    public ZombieBehaviorGoal(Zombie zombie) {
        this.zombie = zombie;
        this.agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
        this.key = GoalKey.of(Zombie.class, new NamespacedKey(TooMuchZombies.getInstance(), "behavior_goal"));
    }

    @Override
    public boolean shouldActivate() {
        return agent != null && zombie.isValid();
    }

    @Override
    public void tick() {
        if (agent != null && !agent.isAiPaused()) {
            // 执行行为
            ZombieAIManager.getInstance().executeBehavior(agent);
        }
    }

    @Override
    public GoalKey<Zombie> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        // 我们没有完全接管移动（Pathfinder 负责那部分），
        // 但我们可能会发布移动指令。
        // 如果我们标记为 MOVE，其他 MOVE 目标可能会暂停。
        // 我们想与其他目标（如 MeleeAttackGoal）并行运行吗？
        // 或者我们想覆盖它们？
        // 设计方案是“混合架构”。
        // 假设我们不声明任何类型，这样我们就会一直运行？
        // 或者如果我们强制看向某处，我们就声明 LOOK。
        return EnumSet.noneOf(GoalType.class); 
    }
}
