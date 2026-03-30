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
            try {
                ZombieAIManager.getInstance().executeBehavior(agent);
            } catch (Throwable t) {
                if (agent.checkAndResetSkillCooldown("AI_GOAL_ERR_LOG", 5000L)) {
                    TooMuchZombies.getInstance().getLogger().warning(
                        "ZombieBehaviorGoal tick failure for " + zombie.getUniqueId() + ": " + t.getClass().getSimpleName()
                    );
                }
            }
        }
    }

    @Override
    public GoalKey<Zombie> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.TARGET);
    }
}
