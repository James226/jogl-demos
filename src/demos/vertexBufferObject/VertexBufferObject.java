/*
 * Portions Copyright (C) 2003 Sun Microsystems, Inc.
 * All rights reserved.
 */

/*
 *
 * COPYRIGHT NVIDIA CORPORATION 2003. ALL RIGHTS RESERVED.
 * BY ACCESSING OR USING THIS SOFTWARE, YOU AGREE TO:
 *
 *  1) ACKNOWLEDGE NVIDIA'S EXCLUSIVE OWNERSHIP OF ALL RIGHTS
 *     IN AND TO THE SOFTWARE;
 *
 *  2) NOT MAKE OR DISTRIBUTE COPIES OF THE SOFTWARE WITHOUT
 *     INCLUDING THIS NOTICE AND AGREEMENT;
 *
 *  3) ACKNOWLEDGE THAT TO THE MAXIMUM EXTENT PERMITTED BY
 *     APPLICABLE LAW, THIS SOFTWARE IS PROVIDED *AS IS* AND
 *     THAT NVIDIA AND ITS SUPPLIERS DISCLAIM ALL WARRANTIES,
 *     EITHER EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED
 *     TO, IMPLIED WARRANTIES OF MERCHANTABILITY  AND FITNESS
 *     FOR A PARTICULAR PURPOSE.
 *
 * IN NO EVENT SHALL NVIDIA OR ITS SUPPLIERS BE LIABLE FOR ANY
 * SPECIAL, INCIDENTAL, INDIRECT, OR CONSEQUENTIAL DAMAGES
 * WHATSOEVER (INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS
 * OF BUSINESS PROFITS, BUSINESS INTERRUPTION, LOSS OF BUSINESS
 * INFORMATION, OR ANY OTHER PECUNIARY LOSS), INCLUDING ATTORNEYS'
 * FEES, RELATING TO THE USE OF OR INABILITY TO USE THIS SOFTWARE,
 * EVEN IF NVIDIA HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 *
 */

package demos.vertexBufferObject;

import java.awt.*;
import java.awt.event.*;
import java.nio.*;
import java.util.*;
import javax.swing.*;

import net.java.games.jogl.*;
import net.java.games.jogl.util.*;

/** <P> A port of NVidia's [tm] Vertex Array Range demonstration to
    OpenGL[tm] for Java[tm], the Java programming language, and the
    vendor-neutral Vertex Buffer Object extension. The current web
    site for the demo is <a href =
    "http://developer.nvidia.com/object/vardemo.html">here</a>. </P>

    <P> This demonstration requires the following:

    <ul>
    <li> A JDK 1.4 implementation
    <li> A card supporting the GL_ARB_vertex_buffer_object extension
         (only in recent drivers)
    </ul>

    </P>

    <P> This demonstration illustrates the use of the java.nio direct
    buffer classes in JDK 1.4 to access memory outside of the Java
    garbage-collected heap, in particular that returned from the call
    glMapBufferARB, to achieve higher performance than accessing the
    same data in system memory allows. </P>
*/

public class VertexBufferObject {
  private boolean[] b = new boolean[256];
  private static final int SIZEOF_FLOAT = 4;
  private static final int STRIP_SIZE  = 48;
  private int tileSize   = 9 * STRIP_SIZE;
  private int numBuffers = 4;
  private int bufferLength = 1000000;
  private int bufferSize   = bufferLength * SIZEOF_FLOAT;
  private static final int SIN_ARRAY_SIZE = 1024;

  private int         bigBufferObject;
  private boolean     vboEnabled = true;
  private ByteBuffer  bigArrayVBOBytes;
  private FloatBuffer bigArrayVBO;
  private FloatBuffer bigArraySystem;
  private FloatBuffer bigArray;
  private int[][]     elements;
  private float[]     xyArray;

  static class VBOBuffer {
    public FloatBuffer vertices;
    public FloatBuffer normals;
    public int         vertexOffset;
    public int         normalOffset;
  }
  private VBOBuffer[] buffers;

  private float[] sinArray;
  private float[] cosArray;

  // Primitive: GL_QUAD_STRIP, GL_LINE_STRIP, or GL_POINTS
  private int primitive = GL.GL_QUAD_STRIP;

  // Animation parameters
  private float hicoef = .06f;
  private float locoef = .10f;
  private float hifreq = 6.1f;
  private float lofreq = 2.5f;
  private float phaseRate = .02f;
  private float phase2Rate = -0.12f;
  private float phase  = 0;
  private float phase2 = 0;

  // Temporaries for computation
  float[] ysinlo = new float[STRIP_SIZE];
  float[] ycoslo = new float[STRIP_SIZE];
  float[] ysinhi = new float[STRIP_SIZE];
  float[] ycoshi = new float[STRIP_SIZE];

  // For thread-safety when dealing with keypresses
  private volatile boolean toggleVBO           = false;
  private volatile boolean toggleLighting      = false;
  private volatile boolean toggleLightingModel = false;
  private volatile boolean recomputeElements   = false;
  private volatile boolean quit                = false;

  // Frames-per-second computation
  private boolean firstProfiledFrame;
  private int     profiledFrameCount;
  private int     numDrawElementsCalls;
  private long startTimeMillis;

  private GLCanvas canvas = null;
  private Animator animator;

  static class PeriodicIterator {
    public PeriodicIterator(int arraySize,
                            float period,
                            float initialOffset,
                            float delta) {
      float arrayDelta =  arraySize * (delta / period); // floating-point steps-per-increment
      increment = (int)(arrayDelta * (1<<16));          // fixed-point steps-per-increment

      float offset = arraySize * (initialOffset / period); // floating-point initial index
      initOffset = (int)(offset * (1<<16));                // fixed-point initial index

        arraySizeMask = 0;
        int i = 20; // array should be reasonably sized...
        while((arraySize & (1<<i)) == 0) {
          i--;
        }
        arraySizeMask = (1<<i)-1;
        index = initOffset;
    }

    public PeriodicIterator(PeriodicIterator arg) {
      this.arraySizeMask = arg.arraySizeMask;
      this.increment = arg.increment;
      this.initOffset = arg.initOffset;
      this.index = arg.index;
    }

    public int getIndex() {
      return (index >> 16) & arraySizeMask;
    }

    public void incr() {
      index += increment;
    }

    public void decr() {
      index -= increment;
    }

    public void reset() {
      index = initOffset;
    }

    //----------------------------------------------------------------------
    // Internals only below this point
    //

    private int arraySizeMask;
    // fraction bits == 16
    private int increment;
    private int initOffset;
    private int index;
  }

  public static void usage(String className) {
    System.out.println("usage: java " + className + " [-slow]");
    System.out.println("-slow flag starts up using data in the Java heap");
    System.exit(0);
  }

  public static void main(String[] args) {
    new VertexBufferObject().run(args);
  }

  public void run(String[] args) {
    if (args.length > 1) {
      usage(getClass().getName());
    }

    if (args.length == 1) {
      if (args[0].equals("-slow")) {
        vboEnabled = false;
      } else {
        usage(getClass().getName());
      }
    }

    setFlag(' ', true);   // animation on
    setFlag('i', true);   // infinite viewer and light

    canvas = GLDrawableFactory.getFactory().createGLCanvas(new GLCapabilities());
    //    canvas.setGL(new TraceGL(canvas.getGL(), System.err));
    //    canvas.setGL(new DebugGL(canvas.getGL()));
    VBOListener listener = new VBOListener();
    canvas.addGLEventListener(listener);

    animator = new Animator(canvas);

    Frame frame = new Frame("Very Simple NV_vertex_array_range demo");
    frame.setLayout(new BorderLayout());
    canvas.setSize(800, 800);
    frame.add(canvas, BorderLayout.CENTER);
    frame.pack();
    frame.show();
    canvas.requestFocus();

    frame.addWindowListener(new WindowAdapter() {
        public void windowClosing(WindowEvent e) {
          animator.stop();
          System.exit(0);
        }
      });

    animator.start();
  }

  //----------------------------------------------------------------------
  // Internals only below this point
  //

  private void setFlag(char key, boolean val) {
    b[((int) key) & 0xFF] = val;
  }

  private boolean getFlag(char key) {
    return b[((int) key) & 0xFF];
  }

  private void initExtension(GL gl, String glExtensionName) {
    if (!gl.isExtensionAvailable(glExtensionName)) {
      final String message = "OpenGL extension \"" + glExtensionName + "\" not available";
      new Thread(new Runnable() {
          public void run() {
            JOptionPane.showMessageDialog(null, message, "Unavailable extension", JOptionPane.ERROR_MESSAGE);
            runExit();
          }
        }).start();
      throw new RuntimeException(message);
    }
  }
  class VBOListener implements GLEventListener {
    boolean exiting = false;

    public void init(GLDrawable drawable) {
      GL  gl  = drawable.getGL();
      GLU glu = drawable.getGLU();

      // Try and disable synch-to-retrace for fastest framerate
      if (gl.isFunctionAvailable("wglSwapIntervalEXT")) {
        System.err.println("wglSwapIntervalEXT available; disabling sync-to-refresh for best framerate");
        gl.wglSwapIntervalEXT(0);       
      }
      else {    
        System.err.println("wglSwapIntervalEXT not available; cannot disable sync-to-refresh");
      }

      System.err.println("Available extensions:");
      System.err.println(gl.glGetString(GL.GL_EXTENSIONS));

      try {
        initExtension(gl, "GL_ARB_vertex_buffer_object");
      } catch (RuntimeException e) {
        quit = true;
        throw (e);
      }      
      
      gl.glEnable(GL.GL_DEPTH_TEST);

      gl.glClearColor(0, 0, 0, 0);

      gl.glEnable(GL.GL_LIGHT0);
      gl.glEnable(GL.GL_LIGHTING);
      gl.glEnable(GL.GL_NORMALIZE);
      gl.glMaterialfv(GL.GL_FRONT_AND_BACK, GL.GL_AMBIENT, new float[]  {.1f, .1f,    0, 1});
      gl.glMaterialfv(GL.GL_FRONT_AND_BACK, GL.GL_DIFFUSE, new float[]  {.6f, .6f,  .1f, 1});
      gl.glMaterialfv(GL.GL_FRONT_AND_BACK, GL.GL_SPECULAR, new float[] { 1,    1, .75f, 1});
      gl.glMaterialf(GL.GL_FRONT_AND_BACK, GL.GL_SHININESS, 128.f);

      gl.glLightfv(GL.GL_LIGHT0, GL.GL_POSITION, new float[] { .5f, 0, .5f, 0});
      gl.glLightModeli(GL.GL_LIGHT_MODEL_LOCAL_VIEWER, 0);

      // NOTE: it looks like GLUT (or something else) sets up the
      // projection matrix in the C version of this demo.
      gl.glMatrixMode(GL.GL_PROJECTION);
      gl.glLoadIdentity();
      glu.gluPerspective(60, 1.0, 0.1, 100);
      gl.glMatrixMode(GL.GL_MODELVIEW);

      allocateBigArray(gl);
      allocateBuffers(gl);

      sinArray = new float[SIN_ARRAY_SIZE];
      cosArray = new float[SIN_ARRAY_SIZE];

      for (int i = 0; i < SIN_ARRAY_SIZE; i++) {
        double step = i * 2 * Math.PI / SIN_ARRAY_SIZE;
        sinArray[i] = (float) Math.sin(step);
        cosArray[i] = (float) Math.cos(step);
      }

      if (vboEnabled) {
        bigArray = bigArrayVBO;
      } else {
        bigArray = bigArraySystem;
      }
      setupBuffers();
      gl.glEnableClientState(GL.GL_VERTEX_ARRAY);
      gl.glEnableClientState(GL.GL_NORMAL_ARRAY);

      computeElements();

      drawable.addKeyListener(new KeyAdapter() {
          public void keyTyped(KeyEvent e) {
            dispatchKey(e.getKeyChar());
          }
        });
    }

    private void allocateBuffers(GL gl) {
      buffers = new VBOBuffer[numBuffers];
      for (int i = 0; i < numBuffers; i++) {
        buffers[i] = new VBOBuffer();
      }
    }

    private void setupBuffers() {
      int sliceSize = bufferLength / numBuffers;
      for (int i = 0; i < numBuffers; i++) {
        int startIndex = i * sliceSize;
        buffers[i].vertices = sliceBuffer(bigArray, startIndex, sliceSize);
        buffers[i].normals  = sliceBuffer(buffers[i].vertices, 3,
                                          buffers[i].vertices.limit() - 3);
        buffers[i].vertexOffset = startIndex       * BufferUtils.SIZEOF_FLOAT;
        buffers[i].normalOffset = (startIndex + 3) * BufferUtils.SIZEOF_FLOAT;
      }
    }

    private void dispatchKey(char k) {
      setFlag(k, !getFlag(k));
      // Quit on escape or 'q'
      if ((k == (char) 27) || (k == 'q')) {
        runExit();
      }

      if (k == 'r') {
        if (getFlag(k)) {
          profiledFrameCount = 0;
          numDrawElementsCalls = 0;
          firstProfiledFrame = true;
        }
      }

      if (k == 'w') {
        if (getFlag(k)) {
          primitive = GL.GL_LINE_STRIP;
        } else {
          primitive = GL.GL_QUAD_STRIP;
        }
      }

      if (k == 'p') {
        if (getFlag(k)) {
          primitive = GL.GL_POINTS;
        } else {
          primitive = GL.GL_QUAD_STRIP;
        }
      }

      if (k == 'v') {
        toggleVBO = true;
      }

      if (k == 'd') {
        toggleLighting = true;
      }

      if (k == 'i') {
        toggleLightingModel = true;
      }

      if('h'==k)
        hicoef += .005;
      if('H'==k)
        hicoef -= .005;
      if('l'==k)
        locoef += .005;
      if('L'==k)
        locoef -= .005;
      if('1'==k)
        lofreq += .1f;
      if('2'==k)
        lofreq -= .1f;
      if('3'==k)
        hifreq += .1f;
      if('4'==k)
        hifreq -= .1f;
      if('5'==k)
        phaseRate += .01f;
      if('6'==k)
        phaseRate -= .01f;
      if('7'==k)
        phase2Rate += .01f;
      if('8'==k)
        phase2Rate -= .01f;

      if('t'==k) {
        if(tileSize < 864) {
          tileSize += STRIP_SIZE;
          recomputeElements = true;
          System.err.println("tileSize = " + tileSize);
        }
      }

      if('T'==k) {
        if(tileSize > STRIP_SIZE) {
          tileSize -= STRIP_SIZE;
          recomputeElements = true;
          System.err.println("tileSize = " + tileSize);
        }
      }
    }

    public void display(GLDrawable drawable) {
      // Don't try to do OpenGL operations if we're tearing things down
      if (quit) {
        return;
      }

      GL  gl  = drawable.getGL();
      GLU glu = drawable.getGLU();

      // Check to see whether to animate
      if (getFlag(' ')) {
        phase += phaseRate;
        phase2 += phase2Rate;

        if (phase > (float) (20 * Math.PI)) {
          phase = 0;
        }

        if (phase2 < (float) (-20 * Math.PI)) {
          phase2 = 0;
        }
      }

      PeriodicIterator loX =
        new PeriodicIterator(SIN_ARRAY_SIZE, (float) (2 * Math.PI), phase, (float) ((1.f/tileSize)*lofreq*Math.PI));
      PeriodicIterator loY = new PeriodicIterator(loX);
      PeriodicIterator hiX =
        new PeriodicIterator(SIN_ARRAY_SIZE, (float) (2 * Math.PI), phase2, (float) ((1.f/tileSize)*hifreq*Math.PI));
      PeriodicIterator hiY = new PeriodicIterator(hiX);

      if (toggleVBO) {
        vboEnabled = !vboEnabled;
        if (!vboEnabled) {
          bigArray = bigArraySystem;
          setupBuffers();
        }
        toggleVBO = false;
      }

      if (toggleLighting) {
        if (getFlag('d')) {
          gl.glDisable(GL.GL_LIGHTING);
        } else {
          gl.glEnable(GL.GL_LIGHTING);
        }
        toggleLighting = false;
      }

      if (toggleLightingModel) {
        if(getFlag('i')) {
          // infinite light
          gl.glLightfv(GL.GL_LIGHT0, GL.GL_POSITION, new float[] { .5f, 0, .5f, 0 });
          gl.glLightModeli(GL.GL_LIGHT_MODEL_LOCAL_VIEWER, 0);
        } else {
          gl.glLightfv(GL.GL_LIGHT0, GL.GL_POSITION, new float[] { .5f, 0, -.5f,1 });
          gl.glLightModeli(GL.GL_LIGHT_MODEL_LOCAL_VIEWER, 1);
        }
        toggleLightingModel = false;
      }

      if (recomputeElements) {
        computeElements();
        recomputeElements = false;
      }

      gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

      gl.glPushMatrix();

      final float[] modelViewMatrix = new float[] {
          1, 0, 0, 0,
          0, 1, 0, 0,
          0, 0, 1, 0,
          0, 0, -1, 1
      };
      gl.glLoadMatrixf(modelViewMatrix);

      // FIXME: add mouse interaction
      // camera.apply_inverse_transform();
      // object.apply_transform();

      int cur = 0;
      int numSlabs = tileSize / STRIP_SIZE;

      if (vboEnabled) {
        gl.glBindBufferARB(GL.GL_ARRAY_BUFFER_ARB, bigBufferObject);
      } else {
        gl.glBindBufferARB(GL.GL_ARRAY_BUFFER_ARB, 0);
      }

      for(int slab = numSlabs; --slab>=0; ) {
        cur = slab % numBuffers;

        if (vboEnabled) {
          ByteBuffer tmp = gl.glMapBufferARB(GL.GL_ARRAY_BUFFER_ARB, GL.GL_WRITE_ONLY_ARB);
          if (tmp == null) {
            throw new RuntimeException("Unable to map vertex buffer object");
          }
          if (tmp != bigArrayVBOBytes) {
            bigArrayVBOBytes = tmp;
            bigArrayVBO = setupBuffer(tmp);
            bigArray = bigArrayVBO;
            setupBuffers();
          }
        }

        FloatBuffer v = buffers[cur].vertices;
        int vertexIndex = 0;

        if (vboEnabled) {
          gl.glVertexPointer(3, GL.GL_FLOAT, 6 * SIZEOF_FLOAT, BufferUtils.bufferOffset(buffers[cur].vertexOffset));
          gl.glNormalPointer(   GL.GL_FLOAT, 6 * SIZEOF_FLOAT, BufferUtils.bufferOffset(buffers[cur].normalOffset));
        } else {
          gl.glVertexPointer(3, GL.GL_FLOAT, 6 * SIZEOF_FLOAT, v);
          gl.glNormalPointer(   GL.GL_FLOAT, 6 * SIZEOF_FLOAT, buffers[cur].normals);
        }

        for(int jj=STRIP_SIZE; --jj>=0; ) {
          ysinlo[jj] = sinArray[loY.getIndex()];
          ycoslo[jj] = cosArray[loY.getIndex()]; loY.incr();
          ysinhi[jj] = sinArray[hiY.getIndex()];
          ycoshi[jj] = cosArray[hiY.getIndex()]; hiY.incr();
        }
        loY.decr();
        hiY.decr();

        for(int i = tileSize; --i>=0; ) {
          float x = xyArray[i];
          int loXIndex = loX.getIndex();
          int hiXIndex = hiX.getIndex();

          int jOffset = (STRIP_SIZE-1)*slab;
          float nx = locoef * -cosArray[loXIndex] + hicoef * -cosArray[hiXIndex];

          // Help the HotSpot Client Compiler by hoisting loop
          // invariant variables into locals. Note that this may be
          // good practice for innermost loops anyway since under
          // the new memory model operations like accidental
          // synchronization may force any compiler to reload these
          // fields from memory, destroying their ability to
          // optimize.
          float locoef_tmp = locoef;
          float hicoef_tmp = hicoef;
          float[] ysinlo_tmp = ysinlo;
          float[] ysinhi_tmp = ysinhi;
          float[] ycoslo_tmp = ycoslo;
          float[] ycoshi_tmp = ycoshi;
          float[] sinArray_tmp = sinArray;
          float[] xyArray_tmp = xyArray;

          for(int j = STRIP_SIZE; --j>=0; ) {
            float y;

            y = xyArray_tmp[j + jOffset];

            float ny;

            v.put(vertexIndex, x);
            v.put(vertexIndex + 1, y);
            v.put(vertexIndex + 2, (locoef_tmp * (sinArray_tmp[loXIndex] + ysinlo_tmp[j]) +
                                    hicoef_tmp * (sinArray_tmp[hiXIndex] + ysinhi_tmp[j])));
            v.put(vertexIndex + 3, nx);
            ny = locoef_tmp * -ycoslo_tmp[j] + hicoef_tmp * -ycoshi_tmp[j];
            v.put(vertexIndex + 4, ny);
            v.put(vertexIndex + 5, .15f); //.15f * (1.f - sqrt(nx * nx + ny * ny));
            vertexIndex += 6;
          }
          loX.incr();
          hiX.incr();
        }
        loX.reset();
        hiX.reset();

        if (vboEnabled) {
          gl.glUnmapBufferARB(GL.GL_ARRAY_BUFFER_ARB);
        }

        for (int i = 0; i < elements.length; i++) {
          ++numDrawElementsCalls;
          gl.glDrawElements(primitive, elements[i].length, GL.GL_UNSIGNED_INT, elements[i]);
          if(getFlag('f')) {
            gl.glFlush();
          }
        }
      }

      gl.glPopMatrix();

      if (getFlag('r')) {
        if (!firstProfiledFrame) {
          if (++profiledFrameCount == 30) {
            long endTimeMillis = System.currentTimeMillis();
            double secs = (endTimeMillis - startTimeMillis) / 1000.0;
            double fps  = 30.0 / secs;
            double ppf  = tileSize * tileSize * 2;
            double mpps = ppf * fps / 1000000.0;
            System.err.println("fps: " + fps + " polys/frame: " + ppf + " million polys/sec: " + mpps +
                               " DrawElements calls/frame: " + (numDrawElementsCalls / 30));
            profiledFrameCount = 0;
            numDrawElementsCalls = 0;
            startTimeMillis = System.currentTimeMillis();
          }
        } else {
          startTimeMillis = System.currentTimeMillis();
          firstProfiledFrame = false;
        }
      }
    }

    public void reshape(GLDrawable drawable, int x, int y, int width, int height) {}

    // Unused routines
    public void displayChanged(GLDrawable drawable, boolean modeChanged, boolean deviceChanged) {}
  } // end class VBOListener

  private void allocateBigArray(GL gl) {
    bigArraySystem = setupBuffer(ByteBuffer.allocateDirect(bufferSize));

    int[] tmp = new int[1];
    gl.glGenBuffersARB(1, tmp);
    bigBufferObject = tmp[0];
    gl.glBindBufferARB(GL.GL_ARRAY_BUFFER_ARB, bigBufferObject);
    // Initialize data store of buffer object
    gl.glBufferDataARB(GL.GL_ARRAY_BUFFER_ARB, bufferSize, null, GL.GL_DYNAMIC_DRAW_ARB);
    bigArrayVBOBytes = gl.glMapBufferARB(GL.GL_ARRAY_BUFFER_ARB, GL.GL_WRITE_ONLY_ARB);
    bigArrayVBO = setupBuffer(bigArrayVBOBytes);
    gl.glUnmapBufferARB(GL.GL_ARRAY_BUFFER_ARB);
    // Unbind buffer; will be bound again in main loop
    gl.glBindBufferARB(GL.GL_ARRAY_BUFFER_ARB, 0);
    
    float megabytes = (bufferSize / 1000000.f);
    System.err.println("Allocated " + megabytes + " megabytes of fast memory");
  }

  private FloatBuffer setupBuffer(ByteBuffer buf) {
    buf.order(ByteOrder.nativeOrder());
    return buf.asFloatBuffer();
  }

  private FloatBuffer sliceBuffer(FloatBuffer array,
                                  int sliceStartIndex, int sliceLength) {
    array.position(sliceStartIndex);
    FloatBuffer ret = array.slice();
    array.position(0);
    ret.limit(sliceLength);
    return ret;
  }

  private void computeElements() {
    xyArray = new float[tileSize];
    for (int i = 0; i < tileSize; i++) {
      xyArray[i] = i / (tileSize - 1.0f) - 0.5f;
    }

    elements = new int[tileSize - 1][];
    for (int i = 0; i < tileSize - 1; i++) {
      elements[i] = new int[2 * STRIP_SIZE];
      for (int j = 0; j < 2 * STRIP_SIZE; j += 2) {
        elements[i][j]   =  i      * STRIP_SIZE + (j / 2);
        elements[i][j+1] = (i + 1) * STRIP_SIZE + (j / 2);
      }
    }
  }

  private void runExit() {
    quit = true;
    // Note: calling System.exit() synchronously inside the draw,
    // reshape or init callbacks can lead to deadlocks on certain
    // platforms (in particular, X11) because the JAWT's locking
    // routines cause a global AWT lock to be grabbed. Run the
    // exit routine in another thread and cause this one to
    // terminate by throwing an exception out of it.
    new Thread(new Runnable() {
        public void run() {
          animator.stop();
          System.exit(0);
        }
      }).start();
  }
}