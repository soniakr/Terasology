/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.begla.blockmania.world;

import com.github.begla.blockmania.Configuration;
import com.github.begla.blockmania.Game;
import com.github.begla.blockmania.blocks.Block;
import com.github.begla.blockmania.generators.*;
import com.github.begla.blockmania.mobs.Slime;
import com.github.begla.blockmania.rendering.Primitives;
import com.github.begla.blockmania.rendering.ShaderManager;
import com.github.begla.blockmania.rendering.TextureManager;
import com.github.begla.blockmania.utilities.FastRandom;
import com.github.begla.blockmania.utilities.MathHelper;
import javolution.util.FastList;
import javolution.util.FastMap;
import javolution.util.FastSet;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import org.newdawn.slick.util.ResourceLoader;
import org.xml.sax.InputSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL11.*;

/**
 * The world of Blockmania. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class World implements RenderableObject {

    /* PLAYER */
    private Player _player;
    /* WORLD GENERATION */
    private final FastMap<String, ChunkGenerator> _chunkGenerators = new FastMap<String, ChunkGenerator>(32);
    private final FastMap<String, ObjectGenerator> _objectGenerators = new FastMap<String, ObjectGenerator>(32);
    /* ------ */
    private final FastRandom _rand;
    /* PROPERTIES */
    private String _title, _seed;
    private Vector3f _spawningPoint;
    private double _time = Configuration.INITIAL_TIME;
    private long _lastDaytimeMeasurement = Game.getInstance().getTime();
    private double _daylight = 1.0f;
    /* RENDERING */
    private FastSet<Chunk> _visibleChunks;
    /* UPDATING & CACHING */
    private final ChunkUpdateManager _chunkUpdateManager = new ChunkUpdateManager(this);
    private final ChunkCache _chunkCache = new ChunkCache(this);
    private boolean _updatingEnabled = false;
    private boolean _updateThreadAlive = true;
    private final Thread _updateThread;
    /* HORIZON */
    private static int _dlSunMoon = -1;
    private static int _dlClouds = -1;
    private static boolean[][] _clouds;
    private final Vector2f _cloudOffset = new Vector2f();
    private final Vector2f _windDirection = new Vector2f(0.25f, 0);
    private double _lastWindUpdate = 0;
    private short _nextWindUpdateInSeconds = 32;
    /* ENTITIES */
    private final FastList<Entity> _entities = new FastList<Entity>();

    /**
     * Initializes a new world for the single player mode.
     *
     * @param title The title/description of the world
     * @param seed  The seed string used to generate the terrain
     * @param p     The player
     */
    public World(String title, String seed) {
        if (title == null) {
            throw new IllegalArgumentException("No title provided.");
        }

        if (title.isEmpty()) {
            throw new IllegalArgumentException("No title provided.");
        }

        if (seed == null) {
            throw new IllegalArgumentException("No seed provided.");
        }

        if (seed.isEmpty()) {
            throw new IllegalArgumentException("No seed provided.");
        }

        this._title = title;
        this._seed = seed;

        // If loading failed accept the given seed
        loadMetaData();

        // Init. generators
        _chunkGenerators.put("terrain", new ChunkGeneratorTerrain(_seed));
        _chunkGenerators.put("forest", new ChunkGeneratorForest(_seed));
        _chunkGenerators.put("resources", new ChunkGeneratorResources(_seed));
        _objectGenerators.put("tree", new ObjectGeneratorTree(this, _seed));
        _objectGenerators.put("pineTree", new ObjectGeneratorPineTree(this, _seed));
        _objectGenerators.put("firTree", new ObjectGeneratorFirTree(this, _seed));
        _objectGenerators.put("cactus", new ObjectGeneratorCactus(this, _seed));

        // Init. random generator
        _rand = new FastRandom(seed.hashCode());
        _visibleChunks = new FastSet();

        _updateThread = new Thread(new Runnable() {

            public void run() {
                while (true) {
                    /*
                     * Checks if the thread should be killed.
                     */
                    if (!_updateThreadAlive) {
                        return;
                    }

                    /*
                     * Puts the thread to sleep 
                     * if updating is disabled.
                     */
                    if (!_updatingEnabled) {
                        synchronized (_updateThread) {
                            try {
                                _updateThread.wait();
                            } catch (InterruptedException ex) {
                                Game.getInstance().getLogger().log(Level.SEVERE, ex.toString());
                            }
                        }
                    }

                    _chunkUpdateManager.processChunkUpdates();
                    updateDaytime();
                }
            }
        });
    }

    /**
     * Stops the updating thread and writes all chunks to disk.
     */
    public void dispose() {
        Game.getInstance().getLogger().log(Level.INFO, "Disposing world {0} and saving all chunks.", _title);

        synchronized (_updateThread) {
            _updateThreadAlive = false;
            _updateThread.notify();
        }

        saveMetaData();
        _chunkCache.writeAllChunksToDisk();
    }

    /**
     * Updates the time of the world. A day in Blockmania takes 12 minutes and the
     * time is updated every 15 seconds.
     */
    private void updateDaytime() {
        if (Game.getInstance().getTime() - _lastDaytimeMeasurement >= 100) {
            setTime(_time + 1f / ((5f * 60f * 10f)));
            _lastDaytimeMeasurement = Game.getInstance().getTime();
        }
    }

    /**
     *
     */
    private void updateDaylight() {
        // Sunrise
        if (_time < 0.1f && _time > 0.0f) {
            _daylight = _time / 0.1f;
        } else if (_time >= 0.1 && _time <= 0.5f) {
            _daylight = 1.0f;
        }

        // Sunset
        if (_time > 0.5f && _time < 0.6f) {
            _daylight = 1.0f - (_time - 0.5f) / 0.1f;
        } else if (_time >= 0.6f && _time <= 1.0f) {
            _daylight = 0.0f;
        }
    }

    /**
     * Init. the static resources.
     */
    public static void init() {
        /*
         * Create cloud array.
         */
        try {
            BufferedImage cloudImage = ImageIO.read(ResourceLoader.getResource("com/github/begla/blockmania/data/clouds.png").openStream());
            _clouds = new boolean[cloudImage.getWidth()][cloudImage.getHeight()];

            for (int x = 0; x < cloudImage.getWidth(); x++) {
                for (int y = 0; y < cloudImage.getHeight(); y++) {
                    if ((cloudImage.getRGB(x, y) & 0x00FFFFFF) != 0) {
                        _clouds[x][y] = true;
                    }
                }
            }
        } catch (IOException ex) {
            Game.getInstance().getLogger().log(Level.SEVERE, null, ex);
        }

        // Init display lists
        _dlClouds = glGenLists(1);
        _dlSunMoon = glGenLists(1);

        generateSunMoonDisplayList();
        generateCloudDisplayList();

    }

    /**
     * Renders the world.
     */
    public void render() {
        if (_player == null)
            return;

        if (!_player.isHeadUnderWater()) {
            /**
             * Sky box.
             */
            _player.applyNormalizedModelViewMatrix();

            glDisable(GL_CULL_FACE);
            glDisable(GL_DEPTH_TEST);

            glBegin(GL_QUADS);
            Primitives.drawSkyBox(getDaylight());
            glEnd();

            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }

        _player.applyPlayerModelViewMatrix();

        renderSunMoon();

        if (!_player.isHeadUnderWater())
            renderClouds();

        /*
        * Render the world from the player's view.
        */
        _player.render();

        /*
         * Transfer the daylight value to the shaders.
         */
        ShaderManager.getInstance().enableShader("chunk");
        int daylight = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "daylight");
        int swimmimg = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "swimming");
        GL20.glUniform1f(daylight, (float) getDaylight());
        GL20.glUniform1i(swimmimg, _player.isHeadUnderWater() ? 1 : 0);
        ShaderManager.getInstance().enableShader("cloud");
        daylight = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("cloud"), "daylight");
        GL20.glUniform1f(daylight, (float) getDaylight());
        ShaderManager.getInstance().enableShader(null);

        renderEntities();
        renderChunks();
    }

    private void renderEntities() {
        for (int i = 0; i < _entities.size(); i++) {
            _entities.get(i).render();
        }
    }

    private void updateEntities() {
        for (int i = 0; i < _entities.size(); i++) {
            _entities.get(i).update();
        }
    }

    private void renderSunMoon() {
        glPushMatrix();
        // Position the sun relatively to the player
        glTranslated(_player.getPosition().x, Configuration.CHUNK_DIMENSIONS.y * 2.0, Configuration.getSettingNumeric("V_DIST_Z") * Configuration.CHUNK_DIMENSIONS.z + _player.getPosition().z);
        glRotatef(-35, 1, 0, 0);

        glColor4f(1f, 1f, 1f, 1.0f);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);

        glEnable(GL_TEXTURE_2D);
        if (isDaytime()) {
            TextureManager.getInstance().bindTexture("sun");
        } else {
            TextureManager.getInstance().bindTexture("moon");
        }

        glCallList(_dlSunMoon);

        glDisable(GL_BLEND);
        glDisable(GL_TEXTURE_2D);
        glPopMatrix();

        glDisable(GL_TEXTURE_2D);
    }

    private void renderClouds() {
        glEnable(GL_BLEND);
        GL11.glBlendFunc(770, 771);

        ShaderManager.getInstance().enableShader("cloud");

        // Render two passes: The first one only writes to the depth buffer, the second one to the frame buffer
        for (int i = 0; i < 2; i++) {
            if (i == 0) {
                glColorMask(false, false, false, false);
            } else {
                glColorMask(true, true, true, true);
            }

            /*
            * Draw clouds.
            */
            if (_dlClouds > 0 && isDaytime()) {
                glPushMatrix();
                glTranslatef(_player.getPosition().x + _cloudOffset.x, 140f, _player.getPosition().z + _cloudOffset.y);
                glCallList(_dlClouds);
                glPopMatrix();
            }
        }

        ShaderManager.getInstance().enableShader(null);
        glDisable(GL_BLEND);
    }

    private FastSet<Chunk> fetchVisibleChunks() {
        FastSet<Chunk> visibleChunks = new FastSet<Chunk>();
        for (int x = -(Configuration.getSettingNumeric("V_DIST_X").intValue() / 2); x < (Configuration.getSettingNumeric("V_DIST_X").intValue() / 2); x++) {
            for (int z = -(Configuration.getSettingNumeric("V_DIST_Z").intValue() / 2); z < (Configuration.getSettingNumeric("V_DIST_Z").intValue() / 2); z++) {

                Chunk c = _chunkCache.loadOrCreateChunk(calcPlayerChunkOffsetX() + x, calcPlayerChunkOffsetZ() + z);

                if (c != null) {
                    if (c.isChunkInFrustum()) {
                        visibleChunks.add(c);
                    }
                }
            }
        }

        return visibleChunks;
    }

    private void renderChunks() {

        ShaderManager.getInstance().enableShader("chunk");

        glEnable(GL_TEXTURE_2D);
        TextureManager.getInstance().bindTexture("terrain");

        _visibleChunks = fetchVisibleChunks();

        for (FastSet.Record n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end; ) {
            Chunk c = _visibleChunks.valueOf(n);

            c.render(false);

            if (Configuration.getSettingBoolean("CHUNK_OUTLINES")) {
                c.getAABB().render();
            }
        }

        for (FastSet.Record n = _visibleChunks.head(), end = _visibleChunks.tail(); (n = n.getNext()) != end; ) {
            for (int i = 0; i < 2; i++) {
                if (i == 0) {
                    glColorMask(false, false, false, false);
                } else {
                    glColorMask(true, true, true, true);
                }
                _visibleChunks.valueOf(n).render(true);
            }
        }

        glDisable(GL_TEXTURE_2D);
        ShaderManager.getInstance().enableShader(null);
    }

    /**
     * Update all dirty display lists.
     */
    public void update() {
        _player.update();
        _chunkUpdateManager.updateDisplayLists();

        updateClouds();

        for (Chunk c : _visibleChunks) {
            c.update();
        }

        updateEntities();
        _chunkCache.freeCache();
    }

    private void updateClouds() {
        // Move the clouds a bit each update
        _cloudOffset.x += _windDirection.x;
        _cloudOffset.y += _windDirection.y;

        if (_cloudOffset.x >= _clouds.length * 16 / 2 || _cloudOffset.x <= -(_clouds.length * 16 / 2)) {
            _windDirection.x = -_windDirection.x;
        } else if (_cloudOffset.y >= _clouds.length * 16 / 2 || _cloudOffset.y <= -(_clouds.length * 16 / 2)) {
            _windDirection.y = -_windDirection.y;
        }

        if (Game.getInstance().getTime() - _lastWindUpdate > _nextWindUpdateInSeconds * 1000) {
            _windDirection.x = (float) _rand.randomDouble() / 8;
            _windDirection.y = (float) _rand.randomDouble() / 8;
            _nextWindUpdateInSeconds = (short) (MathHelper.fastAbs(_rand.randomInt()) % 16 + 32);
            _lastWindUpdate = Game.getInstance().getTime();
        }
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param x The X-coordinate of the block
     * @return The X-coordinate of the chunk
     */
    private int calcChunkPosX(int x) {
        // Offset for negative chunks
        if (x < 0)
            x -= 15;

        return (x / (int) Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param z The Z-coordinate of the block
     * @return The Z-coordinate of the chunk
     */
    private int calcChunkPosZ(int z) {
        // Offset for negative chunks
        if (z < 0)
            z -= 15;

        return (z / (int) Configuration.CHUNK_DIMENSIONS.z);
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The X-coordinate of the block within the world
     * @param x2 The X-coordinate of the chunk within the world
     * @return The X-coordinate of the block within the chunk
     */
    private int calcBlockPosX(int x1, int x2) {
        int blockPos = (x1 - (x2 * (int) Configuration.CHUNK_DIMENSIONS.x));

        assert (blockPos >= 0) : "Every position MUST be positive or zero: " + blockPos;

        return blockPos;
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param z1 The Z-coordinate of the block within the world
     * @param z2 The Z-coordinate of the chunk within the world
     * @return The Z-coordinate of the block within the chunk
     */
    private int calcBlockPosZ(int z1, int z2) {
        int blockPos = (z1 - (z2 * (int) Configuration.CHUNK_DIMENSIONS.z));

        assert (blockPos >= 0) : "Every position MUST be positive or zero: " + blockPos;

        return blockPos;
    }

    /**
     * Places a block of a specific type at a given position and refreshes the
     * corresponding light values.
     *
     * @param x         The X-coordinate
     * @param y         The Y-coordinate
     * @param z         The Z-coordinate
     * @param type      The type of the block to set
     * @param update    If set the affected chunk is queued for updating
     * @param overwrite
     */
    public final void setBlock(int x, int y, int z, byte type, boolean update, boolean overwrite) {
        int chunkPosX = calcChunkPosX(x);
        int chunkPosZ = calcChunkPosZ(z);

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c == null) {
            return;
        }

        if (overwrite || c.getBlock(blockPosX, y, blockPosZ) == 0x0) {

            byte currentValue = getLight(x, y, z, Chunk.LIGHT_TYPE.SUN);

            if (Block.getBlockForType(c.getBlock(blockPosX, y, blockPosZ)).isRemovable()) {
                c.setBlock(blockPosX, y, blockPosZ, type);
            }

            if (update) {

                /*
                 * Update sunlight.
                 */
                c.refreshSunlightAtLocalPos(blockPosX, blockPosZ, true, true);
                byte newValue = getLight(x, y, z, Chunk.LIGHT_TYPE.SUN);

                /*
                 * Spread light of block light sources.
                 */
                byte luminance = Block.getBlockForType(type).getLuminance();

                /*
                 * Is this block glowing?
                 */
                if (luminance > 0) {
                    currentValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);
                    c.setLight(blockPosX, y, blockPosZ, luminance, Chunk.LIGHT_TYPE.BLOCK);
                    newValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);
                } else {
                    currentValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);
                    c.setLight(blockPosX, y, blockPosZ, (byte) 0x0, Chunk.LIGHT_TYPE.BLOCK);
                    newValue = getLight(x, y, z, Chunk.LIGHT_TYPE.BLOCK);
                }

                /*
                 * Update the block light intensity of the current block.
                 */
                c.refreshLightAtLocalPos(blockPosX, y, blockPosZ, Chunk.LIGHT_TYPE.BLOCK);


                /*
                * Spread the light if the luminance is brighter than the
                * current value.
                */
                if (newValue > currentValue) {
                    c.spreadLight(blockPosX, y, blockPosZ, luminance, Chunk.LIGHT_TYPE.BLOCK);
                } else if (newValue < currentValue) {
                    c.unspreadLight(blockPosX, y, blockPosZ, currentValue, Chunk.LIGHT_TYPE.BLOCK);
                }
            }
        }
    }

    /**
     * @param pos
     * @return
     */
    public final byte getBlockAtPosition(Vector3f pos) {
        return getBlock((int) (pos.x + ((pos.x >= 0) ? 0.5f : -0.5f)), (int) (pos.y + ((pos.y >= 0) ? 0.5f : -0.5f)), (int) (pos.z + ((pos.z >= 0) ? 0.5f : -0.5f)));
    }

    /**
     * Returns the block at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The type of the block
     */
    public final byte getBlock(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x);
        int chunkPosZ = calcChunkPosZ(z);

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getBlock(blockPosX, y, blockPosZ);
        }

        return 0;
    }

    /**
     * Returns true if the block is surrounded by blocks within the N4-neighborhood on the xz-plane.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public final boolean isBlockSurrounded(int x, int y, int z) {
        return (getBlock(x + 1, y, z) > 0 || getBlock(x - 1, y, z) > 0 || getBlock(x, y, z + 1) > 0 || getBlock(x, y, z - 1) > 0);
    }

    /**
     * @param x
     * @param z
     * @return
     */
    public final int maxHeightAt(int x, int z) {
        for (int y = (int) Configuration.CHUNK_DIMENSIONS.y - 1; y >= 0; y--) {
            if (getBlock(x, y, z) != 0x0)
                return y;
        }

        return 0;
    }

    /**
     * Returns the light value at the given position.
     *
     * @param x    The X-coordinate
     * @param y    The Y-coordinate
     * @param z    The Z-coordinate
     * @param type
     * @return The light value
     */
    public final byte getLight(int x, int y, int z, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x);
        int chunkPosZ = calcChunkPosZ(z);

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getLight(blockPosX, y, blockPosZ, type);
        }

        if (type == Chunk.LIGHT_TYPE.SUN)
            return 15;
        else
            return 0;
    }

    /**
     * Sets the light value at the given position.
     *
     * @param x      The X-coordinate
     * @param y      The Y-coordinate
     * @param z      The Z-coordinate
     * @param intens The light intensity value
     * @param type
     */
    public void setLight(int x, int y, int z, byte intens, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x);
        int chunkPosZ = calcChunkPosZ(z);

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            c.setLight(blockPosX, y, blockPosZ, intens, type);
        }
    }

    /**
     * TODO
     *
     * @param x
     * @param spreadLight
     * @param refreshSunlight
     * @param z
     */
    public void refreshSunlightAt(int x, int z, boolean spreadLight, boolean refreshSunlight) {
        int chunkPosX = calcChunkPosX(x);
        int chunkPosZ = calcChunkPosZ(z);

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            c.refreshSunlightAtLocalPos(blockPosX, blockPosZ, spreadLight, refreshSunlight);
        }
    }

    /**
     * Recursive light calculation.
     *
     * @param x
     * @param y
     * @param z
     * @param lightValue
     * @param depth
     * @param type
     */
    public void unspreadLight(int x, int y, int z, byte lightValue, int depth, Chunk.LIGHT_TYPE type, FastList<Vector3f> brightSpots) {
        int chunkPosX = calcChunkPosX(x);
        int chunkPosZ = calcChunkPosZ(z);

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
        if (c != null) {
            c.unspreadLight(blockPosX, y, blockPosZ, lightValue, depth, type, brightSpots);
        }
    }

    /**
     * Recursive light calculation.
     *
     * @param x
     * @param y
     * @param z
     * @param lightValue
     * @param depth
     * @param type
     */
    public void spreadLight(int x, int y, int z, byte lightValue, int depth, Chunk.LIGHT_TYPE type) {
        int chunkPosX = calcChunkPosX(x);
        int chunkPosZ = calcChunkPosZ(z);

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = _chunkCache.loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
        if (c != null) {
            c.spreadLight(blockPosX, y, blockPosZ, lightValue, depth, type);
        }
    }

    /**
     * Returns the daylight value.
     *
     * @return The daylight value
     */
    public double getDaylight() {
        return _daylight;
    }

    /**
     * Returns the player.
     *
     * @return The player
     */
    public Player getPlayer() {
        return _player;
    }

    public void setPlayer(Player p) {
        _player = p;
        // Reset the player's position
        resetPlayer();

        for (int i = 0; i < 64; i++) {
            Entity slime = new Slime(this);
            Vector3f slimeSpawningPoint = new Vector3f(_spawningPoint);

            slimeSpawningPoint.x += _rand.randomDouble() * 30f;
            slimeSpawningPoint.z += _rand.randomDouble() * 30f;

            slime.setPosition(slimeSpawningPoint);
            slime.getPosition().y = 100;
            _entities.add(slime);
        }
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) (_player.getPosition().x / Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) (_player.getPosition().z / Configuration.CHUNK_DIMENSIONS.z);
    }


    /**
     * Displays some information about the world formatted as a string.
     *
     * @return String with world information
     */
    @Override
    public String toString() {
        return String.format("world (cdl: %d, cn: %d, cache: %d, ud: %fs, seed: \"%s\", title: \"%s\")", _chunkUpdateManager.updatesDLSize(), _chunkUpdateManager.updatesSize(), _chunkCache.size(), _chunkUpdateManager.getMeanUpdateDuration() / 1000d, _seed, _title);
    }

    /**
     * Starts the updating thread.
     */
    public void startUpdateThread() {
        _updatingEnabled = true;
        _updateThread.start();
    }

    /**
     * Resumes the updating thread.
     */
    public void resumeUpdateThread() {
        _updatingEnabled = true;
        synchronized (_updateThread) {
            _updateThread.notify();
        }
    }

    /**
     * Safely suspends the updating thread.
     */
    public void suspendUpdateThread() {
        _updatingEnabled = false;
    }

    /**
     * Sets the time of the world.
     *
     * @param time The time to set
     */
    public void setTime(double time) {
        _time = time;

        if (_time < 0) {
            _time = 1.0f;
        } else if (_time > 1.0f) {
            _time = 0.0f;
        }

        updateDaylight();
    }

    public ObjectGenerator getObjectGenerator(String s) {
        return _objectGenerators.get(s);
    }

    public ChunkGenerator getChunkGenerator(String s) {
        return _chunkGenerators.get(s);
    }

    /**
     * Returns true if it is daytime.
     *
     * @return
     */
    boolean isDaytime() {
        return _time > 0.075f && _time < 0.575;
    }

    /**
     * Returns true if it is nighttime.
     *
     * @return
     */
    boolean isNighttime() {
        return !isDaytime();
    }

    /**
     * @param x
     * @param z
     * @return
     */
    public Chunk prepareNewChunk(int x, int z) {
        FastList<ChunkGenerator> gs = new FastList<ChunkGenerator>();
        gs.add(getChunkGenerator("terrain"));
        gs.add(getChunkGenerator("resources"));
        gs.add(getChunkGenerator("forest"));

        // Generate a new chunk and return it
        return new Chunk(this, new Vector3f(x, 0, z), gs);
    }

    /**
     *
     */
    public void printPlayerChunkPosition() {
        int chunkPosX = calcChunkPosX((int) _player.getPosition().x);
        int chunkPosZ = calcChunkPosX((int) _player.getPosition().z);
        System.out.println(_chunkCache.getChunkByKey(MathHelper.cantorize(chunkPosX, chunkPosZ)));
    }

    /**
     * @return
     */
    private Vector3f findSpawningPoint() {
        for (; ; ) {
            int randX = (int) (_rand.randomDouble() * 16000f);
            int randZ = (int) (_rand.randomDouble() * 16000f);

            double dens = ((ChunkGeneratorTerrain) getChunkGenerator("terrain")).calcDensity(randX, 32, randZ);

            if (dens >= 0.008 && dens < 0.02)
                return new Vector3f(randX, 32, randZ);
        }
    }

    /**
     * Sets the spawning point to the player's current position.
     */
    public void setSpawningPoint() {
        _spawningPoint = new Vector3f(_player.getPosition());
    }

    /**
     *
     */
    public void resetPlayer() {
        if (_spawningPoint == null) {
            _spawningPoint = findSpawningPoint();
            _player.resetEntity();
            _player.setPosition(_spawningPoint);
        } else {
            _player.resetEntity();
            _player.setPosition(_spawningPoint);
        }
    }

    /**
     * @return
     */
    public String getWorldSavePath() {
        return String.format("SAVED_WORLDS/%s", _title);

    }

    /**
     * @return
     */
    private boolean saveMetaData() {
        if (Game.getInstance().isSandboxed()) {
            return false;
        }

        // Generate the save directory if needed
        File dir = new File(getWorldSavePath());
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Game.getInstance().getLogger().log(Level.SEVERE, "Could not create save directory.");
                return false;
            }
        }

        File f = new File(String.format("%s/Metadata.xml", getWorldSavePath()));

        try {
            f.createNewFile();
        } catch (IOException ex) {
            Game.getInstance().getLogger().log(Level.SEVERE, null, ex);
        }

        Element root = new Element("World");
        Document doc = new Document(root);

        // Save the world metadata
        root.setAttribute("seed", _seed);
        root.setAttribute("title", _title);
        root.setAttribute("time", Double.toString(_time));

        // Save the player metadata
        Element player = new Element("Player");
        player.setAttribute("x", Float.toString(_player.getPosition().x));
        player.setAttribute("y", Float.toString(_player.getPosition().y));
        player.setAttribute("z", Float.toString(_player.getPosition().z));
        root.addContent(player);


        XMLOutputter outputter = new XMLOutputter();
        FileOutputStream output;

        try {
            output = new FileOutputStream(f);

            try {
                outputter.output(doc, output);
            } catch (IOException ex) {
                Game.getInstance().getLogger().log(Level.SEVERE, null, ex);
            }

            return true;
        } catch (FileNotFoundException ex) {
            Game.getInstance().getLogger().log(Level.SEVERE, null, ex);
        }


        return false;
    }

    /**
     * @return
     */
    private boolean loadMetaData() {
        if (Game.getInstance().isSandboxed()) {
            return false;
        }

        File f = new File(String.format("%s/Metadata.xml", getWorldSavePath()));

        try {
            SAXBuilder sxbuild = new SAXBuilder();
            InputSource is = new InputSource(new FileInputStream(f));
            Document doc;
            try {
                doc = sxbuild.build(is);
                Element root = doc.getRootElement();
                Element player = root.getChild("Player");

                _seed = root.getAttribute("seed").getValue();
                _spawningPoint = new Vector3f(Float.parseFloat(player.getAttribute("x").getValue()), Float.parseFloat(player.getAttribute("y").getValue()), Float.parseFloat(player.getAttribute("z").getValue()));
                _title = root.getAttributeValue("title");
                setTime(Float.parseFloat(root.getAttributeValue("time")));

                return true;

            } catch (JDOMException ex) {
                Game.getInstance().getLogger().log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Game.getInstance().getLogger().log(Level.SEVERE, null, ex);
            }

        } catch (FileNotFoundException ex) {
            // Metadata.xml not present
        }

        return false;
    }

    /**
     * @return
     */
    public ChunkCache getChunkCache() {
        return _chunkCache;
    }

    /**
     * @return
     */
    public ChunkUpdateManager getChunkUpdateManager() {
        return _chunkUpdateManager;
    }

    /**
     * Generates the cloud display list.
     */
    private static void generateCloudDisplayList() {
        glNewList(_dlClouds, GL_COMPILE);
        glBegin(GL_QUADS);

        int length = _clouds.length;

        for (int x = 0; x < length; x++) {
            for (int y = 0; y < length; y++) {
                if (_clouds[x][y]) {
                    try {
                        Primitives.drawCloud(16, 16, 16, x * 16f - (length / 2 * 16f), 0, y * 16f - (length / 2 * 16f), !_clouds[x - 1][y], !_clouds[x + 1][y], !_clouds[x][y + 1], !_clouds[x][y - 1]);
                    } catch (Exception e) {

                    }
                }
            }
        }

        glEnd();
        glEndList();
    }

    private static void generateSunMoonDisplayList() {
        glNewList(_dlSunMoon, GL_COMPILE);
        glBegin(GL_QUADS);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3d(-Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 0.0f);
        glVertex3d(Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 1.0f);
        glVertex3d(Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(0.f, 1.0f);
        glVertex3d(-Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glEnd();
        glEndList();
    }

    public FastSet<Chunk> getVisibleChunks() {
        return _visibleChunks;
    }
}
