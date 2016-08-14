package qowyn.ark.tools;

import static java.util.Comparator.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import qowyn.ark.ArkProfile;
import qowyn.ark.ArkSavegame;
import qowyn.ark.ArkTribe;
import qowyn.ark.GameObject;
import qowyn.ark.arrays.ArkArrayInteger;
import qowyn.ark.arrays.ArkArrayObjectReference;
import qowyn.ark.arrays.ArkArrayString;
import qowyn.ark.properties.Property;
import qowyn.ark.properties.PropertyByte;
import qowyn.ark.structs.StructPropertyList;
import qowyn.ark.structs.StructUniqueNetIdRepl;
import qowyn.ark.types.ArkName;
import qowyn.ark.types.ObjectReference;

public class PlayerListCommands {

  private static final Pattern PROFILE_PATTERN = Pattern.compile("\\d+\\.arkprofile");

  private static final Pattern TRIBE_PATTERN = Pattern.compile("\\d+\\.arktribe");

  public static void players(OptionHandler oh) {
    OptionSpec<Void> noPrivacySpec = oh.accepts("no-privacy", "Include privacy related data (SteamID, IP).");
    OptionSpec<String> namingSpec = oh.accepts("naming", "Decides how to name the resulting files.")
        .withRequiredArg().describedAs("steamid|playerid").defaultsTo("steamid");

    OptionSet options = oh.reparse();

    String naming = options.valueOf(namingSpec);

    List<String> params = oh.getParams(options);
    if (params.size() != 2 || oh.wantsHelp()) {
      oh.printCommandHelp();
      System.exit(1);
      return;
    }

    try {
      Path saveGame = Paths.get(params.get(0)).toAbsolutePath();
      Path outputDirectory = Paths.get(params.get(1)).toAbsolutePath();
      Path saveDir = saveGame.getParent();

      Map<Integer, StructPropertyList> tribes = new HashMap<>();
      Filter<Path> tribeFilter = path -> TRIBE_PATTERN.matcher(path.getFileName().toString()).matches();

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(saveDir, tribeFilter)) {
        for (Path path : stream) {
          ArkTribe tribe = new ArkTribe(path.toString());
          StructPropertyList tribeData = tribe.getPropertyValue("TribeData", StructPropertyList.class);
          Number tribeId = tribeData.getPropertyValue("TribeID", Number.class);
          tribes.put(tribeId.intValue(), tribeData);
        }
      }

      Filter<Path> profileFilter = path -> PROFILE_PATTERN.matcher(path.getFileName().toString()).matches();

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(saveDir, profileFilter)) {
        for (Path path : stream) {
          ArkProfile profile = new ArkProfile(path.toString());
          StructPropertyList myData = profile.getPropertyValue("MyData", StructPropertyList.class);

          int playerId = myData.getPropertyValue("PlayerDataID", Number.class).intValue();

          String playerFileName;
          if (naming.equals("steamid")) {
            playerFileName = myData.getPropertyValue("UniqueID", StructUniqueNetIdRepl.class).getNetId() + ".json";
          } else if (naming.equals("playerid")) {
            playerFileName = Integer.toString(playerId) + ".json";
          } else {
            throw new Error();
          }

          Path playerPath = outputDirectory.resolve(playerFileName);

          CommonFunctions.writeJson(playerPath.toString(), generator -> {
            generator.writeStartObject();

            // Player data

            generator.write("id", playerId);
            generator.write("playerName", myData.getPropertyValue("PlayerName", String.class));

            if (options.has(noPrivacySpec)) {
              generator.write("steamId", myData.getPropertyValue("UniqueID", StructUniqueNetIdRepl.class).getNetId());
              generator.write("lastIp", myData.getPropertyValue("SavedNetworkAddress", String.class));
            }

            StructPropertyList characterConfig = myData.getPropertyValue("MyPlayerCharacterConfig", StructPropertyList.class);
            StructPropertyList characterStats = myData.getPropertyValue("MyPersistentCharacterStats", StructPropertyList.class);

            // Character data

            generator.write("name", characterConfig.getPropertyValue("PlayerCharacterName", String.class));
            Number extraLevel = characterStats.getPropertyValue("CharacterStatusComponent_ExtraCharacterLevel", Number.class);
            generator.write("level", extraLevel != null ? extraLevel.intValue() + 1 : 1);
            generator.write("experience", characterStats.getPropertyValue("CharacterStatusComponent_ExperiencePoints", Number.class).floatValue());

            // Engrams

            List<ObjectReference> learnedEngrams = characterStats.getPropertyValue("PlayerState_EngramBlueprints", ArkArrayObjectReference.class);

            if (learnedEngrams != null && !learnedEngrams.isEmpty()) {
              generator.writeStartArray("engrams");
              for (ObjectReference reference : learnedEngrams) {
                String engram = reference.getObjectString().toString();

                if (DataManager.hasItemByBGC(engram)) {
                  engram = DataManager.getItemByBGC(engram).getName();
                }

                generator.write(engram);
              }
              generator.writeEnd();
            }

            // Attributes

            generator.writeStartObject("attributes");
            for (Property<?> property : characterStats.getProperties()) {
              if (property instanceof PropertyByte && property.getNameString().equals("CharacterStatusComponent_NumberOfLevelUpPointsApplied")) {
                PropertyByte attribute = (PropertyByte) property;

                String name = AttributeNames.get(attribute.getIndex());
                if (name == null) {
                  generator.write(Integer.toString(attribute.getIndex()), attribute.getValue().getByteValue());
                } else {
                  generator.write(name, attribute.getValue().getByteValue());
                }
              }
            }
            generator.writeEnd();

            // Tribe

            Number tribeId = myData.getPropertyValue("TribeID", Number.class);
            if (tribeId != null) {
              generator.write("tribeId", tribeId.intValue());
              StructPropertyList tribe = tribes.get(tribeId.intValue());
              if (tribe != null) {
                generator.write("tribeName", tribe.getPropertyValue("TribeName", String.class));

                Number tribeOwnerId = tribe.getPropertyValue("OwnerPlayerDataID", Number.class);
                if (tribeOwnerId != null && tribeOwnerId.intValue() == playerId) {
                  generator.write("tribeOwner", true);
                }

                List<Integer> tribeAdmins = tribe.getPropertyValue("TribeAdmins", ArkArrayInteger.class);
                if (tribeAdmins != null && tribeAdmins.contains(playerId)) {
                  generator.write("tribeAdmin", true);
                }
              }
            }

            generator.writeEnd();
          }, oh);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  public static void tribes(OptionHandler oh) {
    // OptionSpec<Void> noPrivacySpec = oh.accepts("no-privacy", "Include privacy related data
    // (SteamID, IP).");
    OptionSpec<Void> itemsSpec = oh.accepts("items", "Include a list of all items belonging to the tribe.");
    OptionSpec<Void> tamedSpec = oh.accepts("creatures", "Include a list of all tamed dinos of the tribe.");
    OptionSpec<Void> structuresSpec = oh.accepts("structures", "Include a list of all structures belonging to the tribe.");
    // OptionSpec<Void> tribelessSpec = oh.accepts("tribeless", "Put all players without a tribe
    // into the 'tribeless' tribe.");

    OptionSet options = oh.reparse();

    List<String> params = oh.getParams(options);
    if (params.size() != 2 || oh.wantsHelp()) {
      oh.printCommandHelp();
      System.exit(1);
      return;
    }

    try {
      boolean mapNeeded = options.has(itemsSpec) || options.has(tamedSpec) || options.has(structuresSpec);
      if (!oh.isQuiet() && mapNeeded) {
        System.out.println("Need to load map, this may take some time...");
      }

      Path saveGame = Paths.get(params.get(0)).toAbsolutePath();
      Path outputDirectory = Paths.get(params.get(1)).toAbsolutePath();
      Path saveDir = saveGame.getParent();

      ArkSavegame save;
      if (mapNeeded) {
        // We don't need properties of items (yet)
        save = new ArkSavegame(saveGame.toString(), oh.readingOptions());
      } else {
        save = null;
      }

      Filter<Path> tribeFilter = path -> TRIBE_PATTERN.matcher(path.getFileName().toString()).matches();

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(saveDir, tribeFilter)) {
        for (Path path : stream) {
          ArkTribe tribe = new ArkTribe(path.toString());
          StructPropertyList tribeData = tribe.getPropertyValue("TribeData", StructPropertyList.class);

          int tribeId = tribeData.getPropertyValue("TribeID", Number.class).intValue();

          String tribeFileName = tribeData.getPropertyValue("TribeID", Number.class).toString() + ".json";

          Path tribePath = outputDirectory.resolve(tribeFileName);

          CommonFunctions.writeJson(tribePath.toString(), generator -> {
            generator.writeStartObject();

            generator.write("name", tribeData.getPropertyValue("TribeName", String.class));

            // TODO check what happens to abandoned tribes
            int ownerId = tribeData.getPropertyValue("OwnerPlayerDataID", Number.class).intValue();
            List<String> memberNames = tribeData.getPropertyValue("MembersPlayerName", ArkArrayString.class);
            List<Integer> memberIds = tribeData.getPropertyValue("MembersPlayerDataID", ArkArrayInteger.class);
            List<Integer> adminIds = tribeData.getPropertyValue("TribeAdmins", ArkArrayInteger.class);

            if (!memberNames.isEmpty()) {
              generator.writeStartArray("members");

              memberNames.forEach(generator::write);

              generator.writeEnd();
            }

            if (adminIds != null && !adminIds.isEmpty()) {
              generator.writeStartArray("admins");

              for (Integer adminId : adminIds) {
                int index = memberIds.indexOf(adminId);
                if (index > -1) {
                  generator.write(memberNames.get(index));
                }
              }

              generator.writeEnd();
            }

            int ownerIndex = memberIds.indexOf(ownerId);
            if (ownerIndex > -1) {
              generator.write("owner", memberNames.get(ownerIndex));
            }

            List<String> tribeLog = tribeData.getPropertyValue("TribeLog", ArkArrayString.class);

            if (tribeLog != null && !tribeLog.isEmpty()) {
              generator.writeStartArray("tribeLog");

              tribeLog.forEach(generator::write);

              generator.writeEnd();
            }

            if (mapNeeded) {
              Map<ArkName, Integer> structures = new HashMap<>();
              Map<ArkName, Integer> creatures = new HashMap<>();
              Map<ArkName, Integer> items = new HashMap<>();
              Map<ArkName, Integer> blueprints = new HashMap<>();
              // Apparently there is or was a bug in ARK causing certain structures to exist twice
              // within a save
              Set<ArkName> processedList = new HashSet<>();

              for (GameObject object : save.getObjects()) {
                if (object.isItem()) {
                  continue;
                }

                Number targetingTeam = object.getPropertyValue("TargetingTeam", Number.class);
                if (targetingTeam == null || targetingTeam.intValue() != tribeId) {
                  continue;
                }

                if (object.getClassString().contains("_Character_")) {
                  if (!processedList.contains(object.getNames().get(0))) {
                    creatures.merge(object.getClassName(), 1, Integer::sum);
                    processedList.add(object.getNames().get(0));
                  } else {
                    // Duped Creature
                    continue;
                  }
                } else if (!object.hasAnyProperty("LinkedPlayerDataID")) {
                  // Players ain't structures
                  if (!processedList.contains(object.getNames().get(0))) {
                    structures.merge(object.getClassName(), 1, Integer::sum);
                    processedList.add(object.getNames().get(0));
                  } else {
                    // Duped Structure
                    continue;
                  }
                } else {
                  if (!processedList.contains(object.getNames().get(0))) {
                    processedList.add(object.getNames().get(0));
                  } else {
                    // Duped Player
                    continue;
                  }
                }

                ObjectReference inventoryReference = object.getPropertyValue("MyInventoryComponent", ObjectReference.class);
                GameObject inventory = inventoryReference != null ? inventoryReference.getObject(save) : null;

                if (inventory != null) {
                  List<ObjectReference> inventoryItems = inventory.getPropertyValue("InventoryItems", ArkArrayObjectReference.class);
                  List<ObjectReference> slotItems = inventory.getPropertyValue("ItemSlots", ArkArrayObjectReference.class);

                  Consumer<List<ObjectReference>> itemListHandler = list -> {
                    if (list != null) {
                      for (ObjectReference itemReference : list) {
                        GameObject item = itemReference.getObject(save);
                        if (item != null) {
                          if (item.hasAnyProperty("bIsEngram") || item.hasAnyProperty("bHideFromInventoryDisplay")) {
                            continue;
                          }

                          Number itemQuantity = item.getPropertyValue("ItemQuantity", Number.class);
                          int amount = itemQuantity != null ? itemQuantity.intValue() : 1;

                          if (processedList.contains(item.getNames().get(0))) {
                            // happens for players having items in their quick bar
                            continue;
                          }
                          processedList.add(item.getNames().get(0));

                          if (item.hasAnyProperty("bIsBlueprint")) {
                            blueprints.merge(item.getClassName(), amount, Integer::sum);
                          } else {
                            items.merge(item.getClassName(), amount, Integer::sum);
                          }
                        }
                      }
                    }
                  };

                  itemListHandler.accept(inventoryItems);
                  itemListHandler.accept(slotItems);
                }
              }

              if (options.has(structuresSpec)) {
                generator.writeStartArray("structures");

                structures.entrySet().stream().sorted(comparing(Map.Entry::getValue, reverseOrder())).forEach(e -> {
                  generator.writeStartObject();

                  generator.write("name", e.getKey().toString());
                  generator.write("count", e.getValue());

                  generator.writeEnd();
                });

                generator.writeEnd();
              }

              if (options.has(tamedSpec)) {
                generator.writeStartArray("tamed");

                creatures.entrySet().stream().sorted(comparing(Map.Entry::getValue, reverseOrder())).forEach(e -> {
                  generator.writeStartObject();

                  String name = e.getKey().toString();
                  if (DataManager.hasCreature(name)) {
                    name = DataManager.getCreature(name).getName();
                  }

                  generator.write("name", name);
                  generator.write("count", e.getValue());

                  generator.writeEnd();
                });

                generator.writeEnd();
              }

              if (options.has(itemsSpec)) {
                Consumer<Map<ArkName, Integer>> listWriter = map -> {
                  map.entrySet().stream().sorted(comparing(Map.Entry::getValue, reverseOrder())).forEach(e -> {
                    generator.writeStartObject();

                    String name = e.getKey().toString();
                    if (DataManager.hasItem(name)) {
                      name = DataManager.getItem(name).getName();
                    }

                    generator.write("name", name);
                    generator.write("count", e.getValue());

                    generator.writeEnd();
                  });
                };

                generator.writeStartArray("items");

                listWriter.accept(items);

                generator.writeEnd();

                generator.writeStartArray("blueprints");

                listWriter.accept(blueprints);

                generator.writeEnd();
              }
            }

            generator.writeEnd();
          }, oh);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}