package com.dbzburstlimit.recompiled;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen Xbox 360 controller over the game. Drives an SDL virtual gamepad
 * (src/burstlimit_android.cpp), so the game sees an ordinary controller.
 * Hidden while a physical controller is connected.
 */
final class TouchControllerView extends View implements InputManager.InputDeviceListener {
    // SDL_GamepadButton
    private static final int BUTTON_A = 0;
    private static final int BUTTON_B = 1;
    private static final int BUTTON_X = 2;
    private static final int BUTTON_Y = 3;
    private static final int BUTTON_BACK = 4;
    private static final int BUTTON_START = 6;
    private static final int BUTTON_LB = 9;
    private static final int BUTTON_RB = 10;
    private static final int BUTTON_DPAD_UP = 11;
    private static final int BUTTON_DPAD_DOWN = 12;
    private static final int BUTTON_DPAD_LEFT = 13;
    private static final int BUTTON_DPAD_RIGHT = 14;
    private static final int BUTTON_COUNT = 15;
    // SDL_GamepadAxis
    private static final int AXIS_LEFT_X = 0;
    private static final int AXIS_LEFT_Y = 1;
    private static final int AXIS_LEFT_TRIGGER = 4;
    private static final int AXIS_RIGHT_TRIGGER = 5;

    static native void nativeSetButton(int button, boolean down);

    static native void nativeSetAxis(int axis, float value);

    static native void nativeDetach();

    private enum Kind { BUTTON, TRIGGER, STICK, DPAD }

    /** Position and size are fractions: x of the width, y and radius of the height. */
    private static final class Control {
        final Kind kind;
        final String label;
        final int id;  // button or axis
        final float fx;
        final float fy;
        final float fr;
        float cx;
        float cy;
        float r;
        boolean pressed;

        Control(Kind kind, String label, int id, float fx, float fy, float fr) {
            this.kind = kind;
            this.label = label;
            this.id = id;
            this.fx = fx;
            this.fy = fy;
            this.fr = fr;
        }

        boolean hit(float x, float y, float slack) {
            float dx = x - cx;
            float dy = y - cy;
            float reach = r * slack;
            return dx * dx + dy * dy <= reach * reach;
        }
    }

    private final List<Control> controls = new ArrayList<>();
    private final Control stick;
    private final Control dpad;
    private final SparseArray<Control> pointers = new SparseArray<>();
    private final boolean[] buttons = new boolean[BUTTON_COUNT];
    private final boolean[] sentButtons = new boolean[BUTTON_COUNT];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final InputManager inputManager;
    private float stickX;
    private float stickY;
    private float leftTrigger;
    private float rightTrigger;
    private float opacity = 0.55f;
    private boolean physicalController;

    TouchControllerView(Context context) {
        super(context);
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        stick = add(new Control(Kind.STICK, "", 0, 0.15f, 0.70f, 0.16f));
        dpad = add(new Control(Kind.DPAD, "", 0, 0.15f, 0.34f, 0.13f));
        add(new Control(Kind.BUTTON, "A", BUTTON_A, 0.86f, 0.80f, 0.075f));
        add(new Control(Kind.BUTTON, "B", BUTTON_B, 0.86f + 0.075f, 0.64f, 0.075f));
        add(new Control(Kind.BUTTON, "X", BUTTON_X, 0.86f - 0.075f, 0.64f, 0.075f));
        add(new Control(Kind.BUTTON, "Y", BUTTON_Y, 0.86f, 0.48f, 0.075f));
        add(new Control(Kind.TRIGGER, "LT", AXIS_LEFT_TRIGGER, 0.05f, 0.09f, 0.065f));
        add(new Control(Kind.BUTTON, "LB", BUTTON_LB, 0.14f, 0.09f, 0.065f));
        add(new Control(Kind.BUTTON, "RB", BUTTON_RB, 0.86f, 0.09f, 0.065f));
        add(new Control(Kind.TRIGGER, "RT", AXIS_RIGHT_TRIGGER, 0.95f, 0.09f, 0.065f));
        add(new Control(Kind.BUTTON, "BACK", BUTTON_BACK, 0.42f, 0.08f, 0.05f));
        add(new Control(Kind.BUTTON, "START", BUTTON_START, 0.58f, 0.08f, 0.05f));
        stroke.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        setOpacity(opacity);
    }

    private Control add(Control control) {
        controls.add(control);
        return control;
    }

    void setOpacity(float value) {
        opacity = Math.max(0.1f, Math.min(1f, value));
        invalidate();
    }

    void startWatchingControllers() {
        inputManager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        updatePhysicalController();
    }

    void stopWatchingControllers() {
        inputManager.unregisterInputDeviceListener(this);
        releaseAll();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updatePhysicalController();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updatePhysicalController();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updatePhysicalController();
    }

    private void updatePhysicalController() {
        boolean found = false;
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (gamepad && device.isExternal()) {
                found = true;
                break;
            }
        }
        if (found == physicalController) {
            return;
        }
        physicalController = found;
        if (found) {
            releaseAll();
            nativeDetach();
            setVisibility(GONE);
        } else {
            setVisibility(VISIBLE);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        for (Control control : controls) {
            control.cx = control.fx * w;
            control.cy = control.fy * h;
            control.r = control.fr * h;
        }
        stroke.setStrokeWidth(Math.max(2f, h * 0.006f));
        label.setTextSize(h * 0.045f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int alpha = Math.round(255 * opacity);
        for (Control control : controls) {
            switch (control.kind) {
                case STICK:
                    drawCircle(canvas, control.cx, control.cy, control.r, false, alpha / 2);
                    drawCircle(canvas, control.cx + stickX * control.r, control.cy + stickY * control.r,
                        control.r * 0.45f, control.pressed, alpha);
                    break;
                case DPAD:
                    drawDpad(canvas, control, alpha);
                    break;
                default:
                    drawCircle(canvas, control.cx, control.cy, control.r, control.pressed, alpha);
                    float textY = control.cy - (label.descent() + label.ascent()) / 2;
                    label.setColor(Color.argb(alpha, 255, 255, 255));
                    float size = label.getTextSize();
                    if (control.label.length() > 2) {
                        label.setTextSize(size * 0.6f);
                        textY = control.cy - (label.descent() + label.ascent()) / 2;
                    }
                    canvas.drawText(control.label, control.cx, textY, label);
                    label.setTextSize(size);
                    break;
            }
        }
    }

    private void drawCircle(Canvas canvas, float x, float y, float r, boolean pressed, int alpha) {
        fill.setColor(pressed ? Color.argb(alpha, 255, 160, 40) : Color.argb(alpha / 2, 30, 30, 30));
        canvas.drawCircle(x, y, r, fill);
        stroke.setColor(Color.argb(alpha, 255, 255, 255));
        canvas.drawCircle(x, y, r, stroke);
    }

    private void drawDpad(Canvas canvas, Control control, int alpha) {
        float arm = control.r;
        float half = arm * 0.36f;
        int[][] dirs = {{0, -1, BUTTON_DPAD_UP}, {0, 1, BUTTON_DPAD_DOWN},
                        {-1, 0, BUTTON_DPAD_LEFT}, {1, 0, BUTTON_DPAD_RIGHT}};
        stroke.setColor(Color.argb(alpha, 255, 255, 255));
        for (int[] dir : dirs) {
            float cx = control.cx + dir[0] * arm * 0.62f;
            float cy = control.cy + dir[1] * arm * 0.62f;
            boolean pressed = buttons[dir[2]];
            fill.setColor(pressed ? Color.argb(alpha, 255, 160, 40) : Color.argb(alpha / 2, 30, 30, 30));
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, fill);
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, stroke);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Control control = find(event.getX(index), event.getY(index));
                if (control == null) {
                    // Kept anyway, so a second finger on a control still
                    // reaches this view. The settings menu is driven with
                    // the controller buttons.
                    return true;
                }
                pointers.put(event.getPointerId(index), control);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    Control current = pointers.get(id);
                    // Buttons follow the finger (slide from X to A); the stick
                    // keeps it until it lifts.
                    if (current != null && current.kind != Kind.STICK && current.kind != Kind.DPAD
                        && !current.hit(event.getX(i), event.getY(i), 1.1f)) {
                        Control next = find(event.getX(i), event.getY(i));
                        if (next != null && next.kind != Kind.STICK) {
                            pointers.put(id, next);
                        } else {
                            pointers.remove(id);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                pointers.remove(event.getPointerId(index));
                break;
            case MotionEvent.ACTION_CANCEL:
                pointers.clear();
                break;
            default:
                return true;
        }
        update(event, action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
            ? index : -1);
        return true;
    }

    private Control find(float x, float y) {
        Control best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Control control : controls) {
            float slack = control.kind == Kind.STICK ? 1.5f : 1.25f;
            if (!control.hit(x, y, slack)) {
                continue;
            }
            float dx = x - control.cx;
            float dy = y - control.cy;
            float distance = (dx * dx + dy * dy) / (control.r * control.r);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = control;
            }
        }
        return best;
    }

    /** Rebuilds the pad state from the active pointers and sends what changed. */
    private void update(MotionEvent event, int liftedIndex) {
        java.util.Arrays.fill(buttons, false);
        float newStickX = 0;
        float newStickY = 0;
        float newLeftTrigger = 0;
        float newRightTrigger = 0;
        for (Control control : controls) {
            control.pressed = false;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == liftedIndex) {
                continue;
            }
            Control control = pointers.get(event.getPointerId(i));
            if (control == null) {
                continue;
            }
            control.pressed = true;
            float x = event.getX(i);
            float y = event.getY(i);
            switch (control.kind) {
                case BUTTON:
                    buttons[control.id] = true;
                    break;
                case TRIGGER:
                    if (control.id == AXIS_LEFT_TRIGGER) {
                        newLeftTrigger = 1;
                    } else {
                        newRightTrigger = 1;
                    }
                    break;
                case STICK: {
                    float dx = (x - control.cx) / control.r;
                    float dy = (y - control.cy) / control.r;
                    float length = (float) Math.sqrt(dx * dx + dy * dy);
                    if (length > 1) {
                        dx /= length;
                        dy /= length;
                    }
                    newStickX = dx;
                    newStickY = dy;
                    break;
                }
                case DPAD: {
                    float dx = (x - control.cx) / control.r;
                    float dy = (y - control.cy) / control.r;
                    // Diagonals press two directions.
                    final float dead = 0.3f;
                    if (dx < -dead) buttons[BUTTON_DPAD_LEFT] = true;
                    if (dx > dead) buttons[BUTTON_DPAD_RIGHT] = true;
                    if (dy < -dead) buttons[BUTTON_DPAD_UP] = true;
                    if (dy > dead) buttons[BUTTON_DPAD_DOWN] = true;
                    break;
                }
            }
        }
        for (int b = 0; b < BUTTON_COUNT; b++) {
            if (buttons[b] != sentButtons[b]) {
                sentButtons[b] = buttons[b];
                nativeSetButton(b, buttons[b]);
            }
        }
        if (newStickX != stickX || newStickY != stickY) {
            stickX = newStickX;
            stickY = newStickY;
            nativeSetAxis(AXIS_LEFT_X, stickX);
            nativeSetAxis(AXIS_LEFT_Y, stickY);
        }
        if (newLeftTrigger != leftTrigger) {
            leftTrigger = newLeftTrigger;
            nativeSetAxis(AXIS_LEFT_TRIGGER, leftTrigger);
        }
        if (newRightTrigger != rightTrigger) {
            rightTrigger = newRightTrigger;
            nativeSetAxis(AXIS_RIGHT_TRIGGER, rightTrigger);
        }
        invalidate();
    }

    private void releaseAll() {
        pointers.clear();
        for (int b = 0; b < BUTTON_COUNT; b++) {
            buttons[b] = false;
            if (sentButtons[b]) {
                sentButtons[b] = false;
                nativeSetButton(b, false);
            }
        }
        if (stickX != 0 || stickY != 0) {
            stickX = 0;
            stickY = 0;
            nativeSetAxis(AXIS_LEFT_X, 0);
            nativeSetAxis(AXIS_LEFT_Y, 0);
        }
        if (leftTrigger != 0) {
            leftTrigger = 0;
            nativeSetAxis(AXIS_LEFT_TRIGGER, 0);
        }
        if (rightTrigger != 0) {
            rightTrigger = 0;
            nativeSetAxis(AXIS_RIGHT_TRIGGER, 0);
        }
        for (Control control : controls) {
            control.pressed = false;
        }
        invalidate();
    }
}
