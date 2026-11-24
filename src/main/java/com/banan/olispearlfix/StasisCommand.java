package com.banan.olispearlfix;

import com.banan.olispearlfix.binding.Binding;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class StasisCommand implements TabExecutor {
    private final StasisController controller;
    private final RodIdentity rods;

    public StasisCommand(StasisController controller, RodIdentity rods) {
        this.controller = controller;
        this.rods = rods;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда доступна игрокам.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            var bindings = controller.registry().forOwner(player.getUniqueId());
            player.sendMessage("§eВаши камеры: " + bindings.size());
            for (Binding binding : bindings) {
                player.sendMessage(binding.id() + " — " + binding.worldName() + " " + binding.plate().x()
                        + " " + binding.plate().y() + " " + binding.plate().z() + " — " + binding.state());
            }
            return true;
        }
        if (!args[0].equalsIgnoreCase("cancel")) {
            return false;
        }
        Binding binding;
        if (args.length > 1) {
            try {
                binding = controller.registry().get(UUID.fromString(args[1]));
            } catch (IllegalArgumentException exception) {
                player.sendMessage("§cНекорректный UUID камеры.");
                return true;
            }
        } else {
            UUID rodId = rods.read(player.getInventory().getItemInMainHand());
            binding = rodId == null ? null : controller.registry().byRod(rodId);
        }
        if (binding == null) {
            player.sendMessage("§eКамера не найдена. Возьмите её удочку или укажите UUID из /stasis list.");
        } else if (!binding.ownerId().equals(player.getUniqueId()) && !player.hasPermission("olispearlfix.admin")) {
            player.sendMessage("§cОтменить камеру может её владелец или администратор.");
        } else {
            controller.disarm(binding, player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> candidates = new ArrayList<>();
        if (args.length == 1) {
            candidates.addAll(List.of("list", "cancel"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender instanceof Player player) {
            controller.registry().forOwner(player.getUniqueId()).forEach(binding -> candidates.add(binding.id().toString()));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1];
        return candidates.stream().filter(candidate -> candidate.startsWith(prefix)).toList();
    }
}
