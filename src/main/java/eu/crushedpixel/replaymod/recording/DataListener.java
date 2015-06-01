package eu.crushedpixel.replaymod.recording;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.gson.Gson;
import eu.crushedpixel.replaymod.holders.PacketData;
import eu.crushedpixel.replaymod.utils.ReplayFile;
import eu.crushedpixel.replaymod.utils.ReplayFileIO;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class DataListener extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LogManager.getLogger();

    protected File file;
    protected Long startTime = null;
    protected String name;
    protected String worldName;
    protected long lastSentPacket = 0;
    protected boolean alive = true;
    protected DataWriter dataWriter;
    protected Set<String> players = new HashSet<String>();
    private boolean singleplayer;
    private Gson gson = new Gson();

    private final File tempResourcePacksFolder = Files.createTempDir();
    private final Map<Integer, String> requestToHash = new ConcurrentHashMap<Integer, String>();
    private final Map<String, File> resourcePacks = new HashMap<String, File>();

    public DataListener(File file, String name, String worldName, long startTime, boolean singleplayer) throws FileNotFoundException {
        this.file = file;
        this.startTime = startTime;
        this.name = name;
        this.worldName = worldName;
        this.singleplayer = singleplayer;

        System.out.println(worldName);

        FileOutputStream fos = new FileOutputStream(file);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        DataOutputStream out = new DataOutputStream(bos);
        dataWriter = new DataWriter(out);
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
        System.out.println(worldName);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        dataWriter.requestFinish(players);
    }

    protected void recordResourcePack(File file, int requestId) {
        try {
            String hash = Hashing.sha1().hashBytes(Files.toByteArray(file)).toString();
            requestToHash.put(requestId, hash);
            synchronized (resourcePacks) {
                if (!resourcePacks.containsKey(hash)) {
                    File tempFile = new File(tempResourcePacksFolder, hash);
                    FileUtils.copyFile(file, tempFile);
                    resourcePacks.put(hash, tempFile);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to save resource pack.", e);
        }
    }

    public class DataWriter {

        private boolean active = true;

        private ConcurrentLinkedQueue<PacketData> queue = new ConcurrentLinkedQueue<PacketData>();
        private DataOutputStream stream;
        Thread outputThread = new Thread(new Runnable() {

            @Override
            public void run() {

                HashMap<Class, Integer> counts = new HashMap<Class, Integer>();

                while(active) {
                    PacketData dataReciever = queue.poll();
                    if(dataReciever != null) {
                        //write the ByteBuf to the given OutputStream

                        byte[] array = dataReciever.getByteArray();

                        if(array != null) {
                            try {
                                ReplayFileIO.writePacket(dataReciever, stream);
                                stream.flush();
                            } catch(Exception e) {
                                e.printStackTrace();
                            }
                        }

                    } else {
                        try {
                            //let the Thread sleep for 1/4 second and queue up new Packets
                            Thread.sleep(250L);
                        } catch(InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                try {
                    stream.flush();
                    stream.close();
                } catch(Exception e) {
                    e.printStackTrace();
                }

                for(Entry<Class, Integer> entries : counts.entrySet()) {
                    System.out.println(entries.getKey() + "| " + entries.getValue());
                }

            }
        }, "replaymod-packet-writer");

        public DataWriter(DataOutputStream stream) {
            this.stream = stream;
            outputThread.start();
        }

        public void writeData(PacketData data) {
            queue.add(data);
        }

        public void requestFinish(Set<String> players) {
            active = false;

            try {
                String mcversion = Minecraft.getMinecraft().getVersion();
                String[] split = mcversion.split("-");
                if(split.length > 0) {
                    mcversion = split[0];
                }

                String[] pl = players.toArray(new String[players.size()]);

                ReplayMetaData metaData = new ReplayMetaData(singleplayer, worldName, (int) lastSentPacket, startTime, pl, mcversion);
                String json = gson.toJson(metaData);

                File folder = ReplayFileIO.getReplayFolder();

                File archive = new File(folder, name + ReplayFile.ZIP_FILE_EXTENSION);
                archive.createNewFile();

                ReplayFileIO.writeReplayFile(archive, file, metaData, resourcePacks, requestToHash);

                file.delete();
                FileUtils.deleteDirectory(tempResourcePacksFolder);

            } catch(Exception e) {
                e.printStackTrace();
            }
        }

    }

}
