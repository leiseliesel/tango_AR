/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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
package com.projecttango.examples.java.augmentedreality;

import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoPoseData;

import android.content.Context;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.provider.Settings;
import android.text.TextPaint;
import android.util.Log;
import android.view.MotionEvent;
import android.view.animation.LinearInterpolator;

import org.rajawali3d.Object3D;
import org.rajawali3d.animation.Animation;
import org.rajawali3d.animation.Animation3D;
import org.rajawali3d.animation.EllipticalOrbitAnimation3D;
import org.rajawali3d.animation.RotateAroundAnimation3D;
import org.rajawali3d.animation.RotateOnAxisAnimation;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.methods.DiffuseMethod;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.StreamingTexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.Quaternion;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.postprocessing.IPostProcessingComponent;
import org.rajawali3d.postprocessing.PostProcessingManager;
import org.rajawali3d.postprocessing.passes.EffectPass;
import org.rajawali3d.postprocessing.passes.GreyScalePass;
import org.rajawali3d.postprocessing.passes.RenderPass;
import org.rajawali3d.primitives.Cube;
import org.rajawali3d.primitives.Plane;
import org.rajawali3d.primitives.ScreenQuad;
import org.rajawali3d.primitives.Sphere;
import org.rajawali3d.renderer.RajawaliRenderer;

import javax.microedition.khronos.opengles.GL10;

/**
 * Renderer that implements a basic augmented reality scene using Rajawali.
 * It creates a scene with a background quad taking the whole screen, where the color camera is
 * rendered, and a sphere with the texture of the earth floating ahead of the start position of
 * the Tango device.
 */
public class AugmentedRealityRenderer extends RajawaliRenderer {
    private static final String TAG = AugmentedRealityRenderer.class.getSimpleName();

    // Rajawali texture used to render the Tango color camera.
    private ATexture mTangoCameraTexture;


    private PostProcessingManager mEffects;

    // Keeps track of whether the scene camera has been configured.
    private boolean mSceneCameraConfigured;

    public AugmentedRealityRenderer(Context context) {
        super(context);
    }

    private Object3D oCube;
    private Object3D oSphere;
    private Plane oPlane;
    private Plane oText;

    @Override
    protected void initScene() {

        // Create a quad covering the whole background and assign a texture to it where the
        // Tango color camera contents will be rendered.
        setupCamera(0xff888888);

        /*
        * Create a Cube and display next to the cube
        * */
        setupCube();


        /*
        * Create a Sphere and place it initially 4 meters next to the cube
        * */
        setupSphere();


        /*
        * Create a Plane and place it initially 2 meters next to the cube
        * */
        setupPlane();
        setupImage();
        setupText();

    }

    /**
     * Update the scene camera based on the provided pose in Tango start of service frame.
     * The camera pose should match the pose of the camera color at the time the last rendered RGB
     * frame, which can be retrieved with this.getTimestamp();
     * <p/>
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public void updateRenderCameraPose(TangoPoseData cameraPose) {
        float[] rotation = cameraPose.getRotationAsFloats();
        float[] translation = cameraPose.getTranslationAsFloats();
        Quaternion quaternion = new Quaternion(rotation[3], rotation[0], rotation[1], rotation[2]);
        // Conjugating the Quaternion is need because Rajawali uses left handed convention for
        // quaternions.
        getCurrentCamera().setRotation(quaternion.conjugate());
        getCurrentCamera().setPosition(translation[0], translation[1], translation[2]);
    }

    /**
     * It returns the ID currently assigned to the texture where the Tango color camera contents
     * should be rendered.
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public int getTextureId() {
        return mTangoCameraTexture == null ? -1 : mTangoCameraTexture.getTextureId();
    }

    /**
     * We need to override this method to mark the camera for re-configuration (set proper
     * projection matrix) since it will be reset by Rajawali on surface changes.
     */
    @Override
    public void onRenderSurfaceSizeChanged(GL10 gl, int width, int height) {
        super.onRenderSurfaceSizeChanged(gl, width, height);
        mSceneCameraConfigured = false;
    }

    public boolean isSceneCameraConfigured() {
        return mSceneCameraConfigured;
    }

    /**
     * Sets the projection matrix for the scene camera to match the parameters of the color camera,
     * provided by the {@code TangoCameraIntrinsics}.
     */
    public void setProjectionMatrix(float[] matrixFloats) {
        getCurrentCamera().setProjectionMatrix(new Matrix4(matrixFloats));
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset,
                                 float xOffsetStep, float yOffsetStep,
                                 int xPixelOffset, int yPixelOffset) {
    }

    @Override
    public void onTouchEvent(MotionEvent event) {

    }


    @Override
    public void onRender(final long ellapsedTime, final double deltaTime) {
        //
        // -- Important. Call render() on the post processing manager.
        //

        mEffects.render(ellapsedTime, deltaTime);
    }


    public void setupCamera (int color) {
        ScreenQuad backgroundQuad = new ScreenQuad();
        Material tangoCameraMaterial = new Material();
        // setting camera color overlay in hex code (rgbt)
        tangoCameraMaterial.setColor(color);
        tangoCameraMaterial.setColorInfluence(0f);

        // We need to use Rajawali's {@code StreamingTexture} since it sets up the texture
        // for GL_TEXTURE_EXTERNAL_OES rendering
        mTangoCameraTexture =
                new StreamingTexture("camera", (StreamingTexture.ISurfaceListener) null);

        try {
            tangoCameraMaterial.addTexture(mTangoCameraTexture);
            backgroundQuad.setMaterial(tangoCameraMaterial);
        } catch (ATexture.TextureException e) {
            Log.e(TAG, "Exception creating texture for RGB camera contents", e);
        }
        getCurrentScene().addChildAt(backgroundQuad, 0);

        mEffects = new PostProcessingManager(this);

        RenderPass renderPass = new RenderPass(getCurrentScene(), getCurrentCamera(), 0);
        mEffects.addPass(renderPass);

        EffectPass greyScalePass = new GreyScalePass();
        greyScalePass.setRenderToScreen(true);
        mEffects.addPass(greyScalePass);
    }

    public void setupCube () {
        Material mCube = new Material();
        mCube.setColor(0x0000ff00);
        mCube.setColorInfluence(1f);
        mCube.enableLighting(true);
        mCube.setDiffuseMethod(new DiffuseMethod.Lambert());

        // Build a Cube and place it initially three meters forward from the origin.
        oCube = new Cube(0.25f);
        oCube.setMaterial(mCube);
        oCube.setTransparent(true);
        oCube.setColor(0x6600ff00);
        oCube.setPosition(0, 0, -3);
        getCurrentScene().addChild(oCube);
    }

    public void setupSphere () {
        Material mSphere = new Material();
          mSphere.setColor(0x000000ff);
          mSphere.setColorInfluence(1f);
          mSphere.enableLighting(true);
          mSphere.setDiffuseMethod(new DiffuseMethod.Lambert());

        // Build a Cube and place it initially three meters forward from the origin.
        oSphere = new Sphere(0.4f, 10, 10);
        oSphere.setMaterial(mSphere);
        oSphere.setTransparent(true);
        oSphere.setColor(0x660000ff);
        oSphere.setPosition(0, 1, -4);
        getCurrentScene().addChild(oSphere);
    }

    public void setupPlane () {
        Material mPlane = new Material();

        oPlane = new Plane(1f , 1f, 1, 1, 1);
        oPlane.setMaterial(mPlane);
        // the alpha value of argb will only be considered after setting "setTransparent" to true
        oPlane.setTransparent(true);
        oPlane.setColor(0x6600ffff);
        oPlane.setPosition(0.25f, -2, -10);
        oPlane.setDoubleSided(true);
        getCurrentScene().addChild(oPlane);
    }

    public void setupImage () {
        Material mPlane = new Material();
        mPlane.setColor(0x00ff00ff);
        try {
              Texture t = new Texture("instructions", R.drawable.marker);
              mPlane.addTexture(t);
          } catch (ATexture.TextureException e) {
              e.printStackTrace();
          }

        oText = new Plane(.5f , .5f, 1, 1, 1);
        oText.setMaterial(mPlane);
        // the alpha value of argb will only be considered after setting "setTransparent" to true
        oText.setTransparent(true);
        oText.setColor(0x00ffff00);
        oText.setPosition(0.25f, 0, -2);
        oText.setDoubleSided(true);
        getCurrentScene().addChild(oText);
    }

    public void setupText () {

        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setColor(Color.BLACK);

        canvas.drawText("Lorem ipsum", 30, 210, paint);

        Material mText = new Material();

        try {
            Texture t = new Texture("text", bitmap);
            mText.addTexture(t);
        } catch (ATexture.TextureException e) {
            e.printStackTrace();
        }

        oPlane = new Plane(1f , 1f, 1, 1, 1);
        oPlane.setMaterial(mText);
        // the alpha value of argb will only be considered after setting "setTransparent" to true
        oPlane.setTransparent(true);
        oPlane.setColor(0x00000000);
        oPlane.setPosition( 0, 0, -1);
        oPlane.setDoubleSided(true);
        getCurrentScene().addChild(oPlane);

    }


}
