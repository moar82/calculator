/*
* Copyright (C) 2014 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android2.calculator3;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.os.Bundle;
import android.os.Debug;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.android2.calculator3.CalculatorExpressionEvaluator.EvaluateCallback;
import com.android2.calculator3.drawable.AnimatingDrawable;
import com.android2.calculator3.util.TextUtil;
import com.android2.calculator3.view.CalculatorPadLayout;
import com.android2.calculator3.view.CalculatorPadView;
import com.android2.calculator3.view.DisplayOverlay;
import com.android2.calculator3.view.EqualsImageButton;
import com.android2.calculator3.view.FormattedNumberEditText;
import com.android2.calculator3.view.ResizingEditText.OnTextSizeChangeListener;
import com.xlythe.floatingview.AnimationFinishedListener;
import com.xlythe.math.Constants;
import com.xlythe.math.History;
import com.xlythe.math.HistoryEntry;
import com.xlythe.math.Persist;
import com.xlythe.math.Solver;

import io.codetail.animation.SupportAnimator;
import io.codetail.animation.ViewAnimationUtils;
import io.codetail.widget.RevealView;

/**
 * A very basic calculator. Maps button clicks to the display, and solves on each key press.
 * */
public abstract class BasicCalculator extends Activity
        implements OnTextSizeChangeListener, EvaluateCallback, OnLongClickListener {

    protected static final String NAME = "Calculator";

    // instance state keys
    private static final String KEY_CURRENT_STATE = NAME + "_currentState";
    private static final String KEY_CURRENT_EXPRESSION = NAME + "_currentExpression";

    /**
     * Constant for an invalid resource id.
     */
    public static final int INVALID_RES_ID = -1;
    // instance state keys
    private static final String KEY_PANEL = NAME + "_panel";
    private ViewGroup mOverlay;
    private FloatingActionButton mFab;
    private View mTray;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        // If there's an animation in progress, cancel it first to ensure our state is up-to-date.
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
        }

        super.onSaveInstanceState(outState);
        outState.putInt(KEY_CURRENT_STATE, mCurrentState.ordinal());
        outState.putString(KEY_CURRENT_EXPRESSION,
                mTokenizer.getNormalizedExpression(mFormulaEditText.getCleanText()));

        final View advancedPad = findViewById(R.id.pad_advanced);
        final View hexPad = findViewById(R.id.pad_hex);
        final View matrixPad = findViewById(R.id.pad_matrix);

        Panel panel = null;
        if (advancedPad.getVisibility() == View.VISIBLE) {
            panel = Panel.Advanced;
        } else if (hexPad.getVisibility() == View.VISIBLE) {
            panel = Panel.Hex;
        } else if (matrixPad.getVisibility() == View.VISIBLE) {
            panel = Panel.Matrix;
        }
        outState.putSerializable(KEY_PANEL, panel);
    }

    /**
     * Sets up the height / position of the fab and tray
     *
     * Returns true if it requires a relayout
     * */
    protected void initializeLayout() {
        CalculatorPadLayout layout = (CalculatorPadLayout) findViewById(R.id.pad_advanced);
        int rows = layout.getRows();
        int columns = layout.getColumns();

        View parent = (View) mFab.getParent();
        mFab.setTranslationX((mFab.getWidth() - parent.getWidth() / columns) / 2);
        mFab.setTranslationY((mFab.getHeight() - parent.getHeight() / rows) / 2);
    }

    public void showFab() {
        mFab.setVisibility(View.VISIBLE);
        mFab.setScaleX(0.65f);
        mFab.setScaleY(0.65f);
        mFab.animate().scaleX(1f).scaleY(1f).setDuration(100).setListener(null);
        mFab.setImageDrawable(new AnimatingDrawable.Builder(getBaseContext())
                        .frames(
                                R.drawable.fab_open_1,
                                R.drawable.fab_open_2,
                                R.drawable.fab_open_3,
                                R.drawable.fab_open_4,
                                R.drawable.fab_open_5)
                        .build()
        );
        ((Animatable) mFab.getDrawable()).start();
    }

    public void hideFab() {
        if (mFab.getVisibility() == View.VISIBLE) {
            mFab.animate().scaleX(0.65f).scaleY(0.65f).setDuration(100).setListener(new AnimationFinishedListener() {
                @Override
                public void onAnimationFinished() {
                    mFab.setVisibility(View.GONE);
                }
            });
            mFab.setImageDrawable(new AnimatingDrawable.Builder(getBaseContext())
                            .frames(
                                    R.drawable.fab_close_1,
                                    R.drawable.fab_close_2,
                                    R.drawable.fab_close_3,
                                    R.drawable.fab_close_4,
                                    R.drawable.fab_close_5)
                            .build()
            );
            ((Animatable) mFab.getDrawable()).start();
        }
    }

    public void showTray() {
        revealTray(false);
    }

    public void hideTray() {
        if (mTray.getVisibility() != View.VISIBLE) {
            return;
        }
        revealTray(true);
    }

    private void revealTray(boolean reverse) {
        View sourceView = mFab;
        mTray.setVisibility(View.VISIBLE);

        final SupportAnimator revealAnimator;
        final int[] clearLocation = new int[2];
        sourceView.getLocationInWindow(clearLocation);
        clearLocation[0] += sourceView.getWidth() / 2;
        clearLocation[1] += sourceView.getHeight() / 2;
        final int revealCenterX = clearLocation[0] - mTray.getLeft();
        final int revealCenterY = clearLocation[1] - mTray.getTop();
        final double x1_2 = Math.pow(mTray.getLeft() - revealCenterX, 2);
        final double x2_2 = Math.pow(mTray.getRight() - revealCenterX, 2);
        final double y_2 = Math.pow(mTray.getTop() - revealCenterY, 2);
        final float revealRadius = (float) Math.max(Math.sqrt(x1_2 + y_2), Math.sqrt(x2_2 + y_2));

        float start = reverse ? revealRadius : 0;
        float end = reverse ? 0 : revealRadius;
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            // The lollipop reveal uses local cords, so use tray height / 2
            revealAnimator =
                    ViewAnimationUtils.createCircularReveal(mTray,
                            revealCenterX, mTray.getHeight() / 2, start, end);
        } else {
            // The legacy support doesn't work with gravity bottom, so use the global cords
            revealAnimator =
                    ViewAnimationUtils.createCircularReveal(mTray,
                            revealCenterX, revealCenterY, start, end);
        }
        revealAnimator.setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime));
        if (reverse) {
            revealAnimator.addListener(new AnimationFinishedListener() {
                @Override
                public void onAnimationFinished() {
                    mTray.setVisibility(View.INVISIBLE);
                }
            });
        }
        play(revealAnimator);
    }

    private void setupTray(Bundle savedInstanceState) {
        final View advancedPad = findViewById(R.id.pad_advanced);
        final View hexPad = findViewById(R.id.pad_hex);
        final View matrixPad = findViewById(R.id.pad_matrix);
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View layout = null;
                switch (v.getId()) {
                    case R.id.btn_advanced:
                        layout = advancedPad;
                        break;
                    case R.id.btn_hex:
                        layout = hexPad;
                        break;
                    case R.id.btn_matrix:
                        layout = matrixPad;
                        break;
                    case R.id.btn_close:
                        // Special case. This button just closes the tray.
                        showFab();
                        hideTray();
                        return;
                }
                showPage(layout);
                showFab();
                hideTray();
            }
        };
        OnLongClickListener longClickListener = new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Toast toast = Toast.makeText(v.getContext(), v.getContentDescription(), Toast.LENGTH_SHORT);
                // Adjust the toast so it's centered over the button
                positionToast(toast, v, getWindow(), 0, (int) (getResources().getDisplayMetrics().density * -5));
                toast.show();
                return true;
            }

            public void positionToast(Toast toast, View view, Window window, int offsetX, int offsetY) {
                // toasts are positioned relatively to decor view, views relatively to their parents, we have to gather additional data to have a common coordinate system
                Rect rect = new Rect();
                window.getDecorView().getWindowVisibleDisplayFrame(rect);
                // covert anchor view absolute position to a position which is relative to decor view
                int[] viewLocation = new int[2];
                view.getLocationInWindow(viewLocation);
                int viewLeft = viewLocation[0] - rect.left;
                int viewTop = viewLocation[1] - rect.top;

                // measure toast to center it relatively to the anchor view
                DisplayMetrics metrics = new DisplayMetrics();
                window.getWindowManager().getDefaultDisplay().getMetrics(metrics);
                int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.UNSPECIFIED);
                int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.UNSPECIFIED);
                toast.getView().measure(widthMeasureSpec, heightMeasureSpec);
                int toastWidth = toast.getView().getMeasuredWidth();

                // compute toast offsets
                int toastX = viewLeft + (view.getWidth() - toastWidth) / 2 + offsetX;
                int toastY = viewTop - toast.getView().getMeasuredHeight() + offsetY;

                toast.setGravity(Gravity.LEFT | Gravity.TOP, toastX, toastY);
            }
        };

        int[] buttons = {R.id.btn_advanced, R.id.btn_hex, R.id.btn_matrix, R.id.btn_close};
        for (int resId : buttons) {
            View button = mTray.findViewById(resId);
            button.setOnClickListener(listener);
            button.setOnLongClickListener(longClickListener);
        }
        Panel panel = (Panel) savedInstanceState.getSerializable(KEY_PANEL);
        if (panel != null) {
            switch (panel) {
                case Advanced:
                    showPage(advancedPad);
                    break;
                case Hex:
                    showPage(hexPad);
                    break;
                case Matrix:
                    showPage(matrixPad);
                    break;
            }
        } else {
            showPage(advancedPad);
        }
    }

    private void showPage(View layout) {
        ViewGroup baseOverlay = mOverlay;
        for (int i = 0; i < baseOverlay.getChildCount(); i++) {
            View child = baseOverlay.getChildAt(i);
            if (child != layout) {
                child.setVisibility(View.GONE);
            } else {
                child.setVisibility(View.VISIBLE);
            }
        }
    }

    protected enum CalculatorState {
        INPUT, EVALUATE, RESULT, ERROR, GRAPHING
    }

    private final TextWatcher mFormulaTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence charSequence, int start, int count, int after) {}

        @Override
        public void afterTextChanged(Editable editable) {
            if (mCurrentState != CalculatorState.GRAPHING) {
                setState(CalculatorState.INPUT);
            }
            mEvaluator.evaluate(editable, BasicCalculator.this);
        }
    };

    private final OnKeyListener mFormulaOnKeyListener = new OnKeyListener() {
        @Override
        public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                        mCurrentButton = mEqualButton;
                        onEquals();
                    }
                    // ignore all other actions
                    return true;
            }
            return false;
        }
    };

    private CalculatorState mCurrentState;
    public CalculatorExpressionTokenizer mTokenizer;
    CalculatorExpressionEvaluator mEvaluator;
    DisplayOverlay mDisplayView;
    public FormattedNumberEditText mFormulaEditText;
    private TextView mResultEditText;
    private CalculatorPadView mPadViewPager;
    private View mDeleteButton;
    private EqualsImageButton mEqualButton;
    private View mClearButton;
    private View mCurrentButton;
    private Animator mCurrentAnimator;
    public History mHistory;
    private HistoryAdapter mHistoryAdapter;
    private Persist mPersist;
    public final ViewGroup.LayoutParams mLayoutParams = new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
    public ViewGroup mDisplayForeground;

    @Override
    protected void onStop() {
        super.onStop();
        Debug.stopMethodTracing();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Debug.startMethodTracing("/mnt/sdcard/traces/appName", 500000000);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calculator);
        savedInstanceState = savedInstanceState == null ? Bundle.EMPTY : savedInstanceState;
        initialize(savedInstanceState);
        mEvaluator.evaluate(mFormulaEditText.getCleanText(), this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Load up to date history
        mPersist = new Persist(this);
        mPersist.load();
        mHistory = mPersist.getHistory();
        incrementGroupId();

        // When history is open, the display is saved as a Display Entry. Cache it if it exists.
        HistoryEntry displayEntry = null;
        if (mHistoryAdapter != null) {
            displayEntry = mHistoryAdapter.getDisplayEntry();
        }

        // Create a new History Adapter (with the up-to-date history)
        mHistoryAdapter = new HistoryAdapter(this, mEvaluator.getSolver(), mHistory);
        mHistoryAdapter.setOnItemClickListener(new HistoryAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(final HistoryEntry entry) {
                mDisplayView.collapse(new AnimationFinishedListener() {
                    @Override
                    public void onAnimationFinished() {
                        if (mHistoryAdapter.hasGraph(entry.getFormula())) {
                            mFormulaEditText.setText(entry.getFormula());
                        } else {
                            mFormulaEditText.insert(entry.getResult());
                        }
                    }
                });
            }
        });
        mHistoryAdapter.setOnItemLongclickListener(new HistoryAdapter.OnItemLongClickListener() {
            @Override
            public void onItemLongClick(HistoryEntry entry) {
                Clipboard.copy(getBaseContext(), entry.getResult());
            }
        });

        // Restore the Display Entry (if it existed)
        if (displayEntry != null) {
            mHistoryAdapter.setDisplayEntry(displayEntry.getFormula(), displayEntry.getResult());
        }

        // Observe! Set! Typical adapter stuff.
        mHistory.setObserver(new History.Observer() {
            @Override
            public void notifyDataSetChanged() {
                mHistoryAdapter.notifyDataSetChanged();
            }
        });
        mDisplayView.setAdapter(mHistoryAdapter);
        mDisplayView.mDisplayGraph.attachToRecyclerView(new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                if (viewHolder.getAdapterPosition() < mHistory.getEntries().size()) {
                    HistoryEntry item = mHistory.getEntries().get(viewHolder.getAdapterPosition());
                    mHistory.remove(item);
                    mHistoryAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                }
            }
        }), mDisplayView);
        mDisplayView.scrollToMostRecent();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mEvaluator.saveHistory(mFormulaEditText.getCleanText(), TextUtil.getCleanText(mResultEditText, mEvaluator.getSolver()), true, this);
        mPersist.save();
    }

    protected void initialize(Bundle savedInstanceState) {
        // Rebuild constants. If the user changed their locale, it won't kill the app
        // but it might change a decimal point from . to ,
        Constants.rebuildConstants();

        mDisplayView = (DisplayOverlay) findViewById(R.id.display);
        mDisplayView.mDisplayGraph.setFade(findViewById(R.id.history_fade), mDisplayView);
        mDisplayForeground = (ViewGroup) findViewById(R.id.the_clear_animation);
        mFormulaEditText = (FormattedNumberEditText) findViewById(R.id.formula);
        mResultEditText = (TextView) findViewById(R.id.result);
        mPadViewPager = (CalculatorPadView) findViewById(R.id.pad_pager);
        mDeleteButton = findViewById(R.id.del);
        mClearButton = findViewById(R.id.clr);
        mEqualButton = (EqualsImageButton) findViewById(R.id.pad_numeric).findViewById(R.id.eq);

        if (mEqualButton == null || mEqualButton.getVisibility() != View.VISIBLE) {
            mEqualButton = (EqualsImageButton) findViewById(R.id.pad_operator).findViewById(R.id.eq);
        }

        mTokenizer = new CalculatorExpressionTokenizer(this);
        mEvaluator = new CalculatorExpressionEvaluator(mTokenizer);

        setState(CalculatorState.values()[
                savedInstanceState.getInt(KEY_CURRENT_STATE, CalculatorState.INPUT.ordinal())]);

        mFormulaEditText.setSolver(mEvaluator.getSolver());
        mFormulaEditText.setText(mTokenizer.getLocalizedExpression(
                savedInstanceState.getString(KEY_CURRENT_EXPRESSION, "")));
        mFormulaEditText.addTextChangedListener(mFormulaTextWatcher);
        mFormulaEditText.setOnKeyListener(mFormulaOnKeyListener);
        mFormulaEditText.setOnTextSizeChangeListener(this);
        mDeleteButton.setOnLongClickListener(this);
        findViewById(R.id.lparen).setOnLongClickListener(this);
        findViewById(R.id.rparen).setOnLongClickListener(this);
        findViewById(R.id.fun_sin).setOnLongClickListener(this);
        findViewById(R.id.fun_cos).setOnLongClickListener(this);
        findViewById(R.id.fun_tan).setOnLongClickListener(this);

        // Disable IME for this application
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        Button dot = (Button) findViewById(R.id.dec_point);
        dot.setText(String.valueOf(Constants.DECIMAL_POINT));

        mOverlay = (ViewGroup) findViewById(R.id.overlay);
        mTray = findViewById(R.id.tray);
        mFab = (FloatingActionButton) findViewById(R.id.fab);

        mFab.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    mFab.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } else {
                    mFab.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                }
                initializeLayout();
            }
        });
        mFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTray();
                hideFab();
            }
        });
        setupTray(savedInstanceState);
        if (findViewById(R.id.pad_pager) == null) {
            showFab();
        }
    }

    protected void setState(CalculatorState state) {
        if (mCurrentState != state) {
            mCurrentState = state;
            mDisplayView.invalidateEqualsButton();

            if (state == CalculatorState.RESULT || state == CalculatorState.ERROR) {
                mDeleteButton.setVisibility(View.GONE);
                mClearButton.setVisibility(View.VISIBLE);
            } else {
                mDeleteButton.setVisibility(View.VISIBLE);
                mClearButton.setVisibility(View.GONE);
            }

            if (state == CalculatorState.ERROR) {
                final int errorColor = getResources().getColor(R.color.calculator_error_color);
                mFormulaEditText.setTextColor(errorColor);
                mResultEditText.setTextColor(errorColor);
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    getWindow().setStatusBarColor(errorColor);
                }
            } else {
                mFormulaEditText.setTextColor(
                        getResources().getColor(R.color.display_formula_text_color));
                mResultEditText.setTextColor(
                        getResources().getColor(R.color.display_result_text_color));
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    getWindow().setStatusBarColor(
                            getResources().getColor(R.color.calculator_accent_color));
                }
            }
        }
    }

    protected CalculatorState getState() {
        return mCurrentState;
    }

    @Override
    public void onBackPressed() {
        if (mDisplayView.mDisplayGraph.isExpanded(mDisplayView)) {
            mDisplayView.collapse();
        } else if (mPadViewPager != null && mPadViewPager.isExpanded()) {
            mPadViewPager.collapse();
        } else {
            super.onBackPressed();
        }
    }

    public void onButtonClick(View view) {
        mCurrentButton = view;
        switch (view.getId()) {
            case R.id.eq:
                onEquals();
                break;
            case R.id.del:
                mTokenizer.onDelete(this);
                break;
            case R.id.clr:
                onClear();
                break;
            case R.id.parentheses:
                mFormulaEditText.setText('(' + mFormulaEditText.getCleanText() + ')');
                break;
            case R.id.fun_cos:
            case R.id.fun_sin:
            case R.id.fun_tan:
            case R.id.fun_ln:
            case R.id.fun_log:
            case R.id.fun_det:
            case R.id.fun_transpose:
            case R.id.fun_inverse:
            case R.id.fun_trace:
            case R.id.fun_norm:
            case R.id.fun_polar:
                // Add left parenthesis after functions.
                insert(((Button) view).getText() + "(");
                break;
            case R.id.op_add:
            case R.id.op_sub:
            case R.id.op_mul:
            case R.id.op_div:
            case R.id.op_fact:
            case R.id.op_pow:
                mFormulaEditText.insert(((Button) view).getText().toString());
                break;
            default:
                insert(((Button) view).getText().toString());
                break;
        }
    }

    @Override
    public boolean onLongClick(View view) {
        mCurrentButton = view;
        switch (view.getId()) {
            case R.id.del:
                mEvaluator.saveHistory(mFormulaEditText.getCleanText(), TextUtil.getCleanText(mResultEditText, mEvaluator.getSolver()), true, this);
                onClear();
                return true;
            case R.id.lparen:
            case R.id.rparen:
                mFormulaEditText.setText('(' + mFormulaEditText.getCleanText() + ')');
                return true;
            case R.id.fun_sin:
                insert(getString(R.string.fun_arcsin) + "(");
                return true;
            case R.id.fun_cos:
                insert(getString(R.string.fun_arccos) + "(");
                return true;
            case R.id.fun_tan:
                insert(getString(R.string.fun_arctan) + "(");
                return true;
        }
        return false;
    }

    /**
     * Inserts text into the formula EditText. If an equation was recently solved, it will
     * replace the formula's text instead of appending.
     * */
    protected void insert(String text) {
        // Add left parenthesis after functions.
        if(mCurrentState.equals(CalculatorState.INPUT) ||
                mCurrentState.equals(CalculatorState.GRAPHING) ||
                mFormulaEditText.isCursorModified()) {
            mFormulaEditText.insert(text);
        }
        else {
            mFormulaEditText.setText(text);
            incrementGroupId();
        }
    }

    @Override
    public void onEvaluate(String expr, String result, int errorResourceId) {
        if (mCurrentState == CalculatorState.INPUT || mCurrentState == CalculatorState.GRAPHING) {
            if (result == null || Solver.equal(result, expr)) {
                mResultEditText.setText(null);
            } else {
                mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
            }
        } else if (errorResourceId != INVALID_RES_ID) {
            onError(errorResourceId);
        } else if (mEvaluator.saveHistory(expr, result, true, this)) {
            mDisplayView.scrollToMostRecent();
            onResult(result);
        } else if (mCurrentState == CalculatorState.EVALUATE) {
            // The current expression cannot be evaluated -> return to the input state.
            setState(CalculatorState.INPUT);
        }
        mDisplayView.invalidateEqualsButton();
    }

    protected void incrementGroupId() {
        mHistory.incrementGroupId();
    }

    @Override
    public void onTextSizeChanged(final TextView textView, float oldSize) {
        if (mCurrentState != CalculatorState.INPUT) { // TODO dont animate when showing graph
            // Only animate text changes that occur from user input.
            return;
        }

        // Calculate the values needed to perform the scale and translation animations,
        // maintaining the same apparent baseline for the displayed text.
        final float textScale = oldSize / textView.getTextSize();
        final float translationX;
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            translationX = (1.0f - textScale) *
                    (textView.getWidth() / 2.0f - textView.getPaddingEnd());
        }
        else {
            translationX = (1.0f - textScale) *
                    (textView.getWidth() / 2.0f - textView.getPaddingRight());
        }
        final float translationY = (1.0f - textScale) *
                (textView.getHeight() / 2.0f - textView.getPaddingBottom());
        final AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(textView, View.SCALE_X, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.SCALE_Y, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_X, translationX, 0.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_Y, translationY, 0.0f));
        animatorSet.setDuration(getResources().getInteger(android.R.integer.config_mediumAnimTime));
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.start();
    }

    protected void onEquals() {
        String text = mFormulaEditText.getCleanText();
        if (mCurrentState == CalculatorState.INPUT) {
            switch(mEqualButton.getState()) {
                case EQUALS:
                    setState(CalculatorState.EVALUATE);
                    mEvaluator.evaluate(text, this);
                    break;
                case NEXT:
                    mFormulaEditText.next();
                    break;
            }
        } else if (mCurrentState == CalculatorState.GRAPHING) {
            setState(CalculatorState.EVALUATE);
            onEvaluate(text, "", INVALID_RES_ID);
        }
    }

    private void reveal(View sourceView, int colorRes, final AnimatorListener listener) {
        // Make reveal cover the display
        final RevealView revealView = new RevealView(this);
        revealView.setLayoutParams(mLayoutParams);
        revealView.setRevealColor(getResources().getColor(colorRes));
        mDisplayForeground.addView(revealView);

        final SupportAnimator revealAnimator;
        final int[] clearLocation = new int[2];
        if (sourceView != null) {
            sourceView.getLocationInWindow(clearLocation);
            clearLocation[0] += sourceView.getWidth() / 2;
            clearLocation[1] += sourceView.getHeight() / 2;
        } else {
            clearLocation[0] = mDisplayForeground.getWidth() / 2;
            clearLocation[1] = mDisplayForeground.getHeight() / 2;
        }
        final int revealCenterX = clearLocation[0] - revealView.getLeft();
        final int revealCenterY = clearLocation[1] - revealView.getTop();
        final double x1_2 = Math.pow(revealView.getLeft() - revealCenterX, 2);
        final double x2_2 = Math.pow(revealView.getRight() - revealCenterX, 2);
        final double y_2 = Math.pow(revealView.getTop() - revealCenterY, 2);
        final float revealRadius = (float) Math.max(Math.sqrt(x1_2 + y_2), Math.sqrt(x2_2 + y_2));

        revealAnimator =
                ViewAnimationUtils.createCircularReveal(revealView,
                        revealCenterX, revealCenterY, 0.0f, revealRadius);
        revealAnimator.setDuration(
                getResources().getInteger(android.R.integer.config_longAnimTime));
        revealAnimator.addListener(listener);

        final Animator alphaAnimator = ObjectAnimator.ofFloat(revealView, View.ALPHA, 0.0f);
        alphaAnimator.setDuration(getResources().getInteger(android.R.integer.config_mediumAnimTime));
        alphaAnimator.addListener(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                mDisplayForeground.removeView(revealView);
            }
        });

        revealAnimator.addListener(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                play(alphaAnimator);
            }
        });
        play(revealAnimator);
    }

    public void play(Animator animator) {
        mCurrentAnimator = animator;
        animator.addListener(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                mCurrentAnimator = null;
            }
        });
        animator.start();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
            mCurrentAnimator = null;
        }
    }

    protected void onClear() {
        if (TextUtils.isEmpty(mFormulaEditText.getCleanText())) {
            return;
        }
        reveal(mCurrentButton, R.color.calculator_accent_color, new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                mFormulaEditText.clear();
                incrementGroupId();
            }
        });
    }

    protected void onError(final int errorResourceId) {
        if (mCurrentState != CalculatorState.EVALUATE) {
            // Only animate error on evaluate.
            mResultEditText.setText(errorResourceId);
            return;
        }

        reveal(mCurrentButton, R.color.calculator_error_color, new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                setState(CalculatorState.ERROR);
                mResultEditText.setText(errorResourceId);
            }
        });
    }

    protected void onResult(final String result) {
        // Calculate the values needed to perform the scale and translation animations,
        // accounting for how the scale will affect the final position of the text.
        final float resultScale =
                mFormulaEditText.getVariableTextSize(result) / mResultEditText.getTextSize();
        final float resultTranslationX = (1.0f - resultScale) *
                (mResultEditText.getWidth() / 2.0f - mResultEditText.getPaddingRight());

        // Calculate the height of the formula (without padding)
        final float formulaRealHeight = mFormulaEditText.getHeight()
                - mFormulaEditText.getPaddingTop()
                - mFormulaEditText.getPaddingBottom();

        // Calculate the height of the resized result (without padding)
        final float resultRealHeight = resultScale *
                (mResultEditText.getHeight()
                        - mResultEditText.getPaddingTop()
                        - mResultEditText.getPaddingBottom());

        // Now adjust the result upwards!
        final float resultTranslationY =
                // Move the result up (so both formula + result heights match)
                - mFormulaEditText.getHeight()
                        // Now switch the result's padding top with the formula's padding top
                        - resultScale * mResultEditText.getPaddingTop()
                        + mFormulaEditText.getPaddingTop()
                        // But the result centers its text! And it's taller now! So adjust for that centered text
                        + (formulaRealHeight - resultRealHeight) / 2;

        // Move the formula all the way to the top of the screen
        final float formulaTranslationY = -mFormulaEditText.getBottom();

        // Use a value animator to fade to the final text color over the course of the animation.
        final int resultTextColor = mResultEditText.getCurrentTextColor();
        final int formulaTextColor = mFormulaEditText.getCurrentTextColor();
        final ValueAnimator textColorAnimator =
                ValueAnimator.ofObject(new ArgbEvaluator(), resultTextColor, formulaTextColor);
        textColorAnimator.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                mResultEditText.setTextColor((Integer) valueAnimator.getAnimatedValue());
            }
        });
        mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
        mResultEditText.setPivotX(mResultEditText.getWidth() / 2);
        mResultEditText.setPivotY(0f);

        final AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                textColorAnimator,
                ObjectAnimator.ofFloat(mResultEditText, View.SCALE_X, resultScale),
                ObjectAnimator.ofFloat(mResultEditText, View.SCALE_Y, resultScale),
                ObjectAnimator.ofFloat(mResultEditText, View.TRANSLATION_X, resultTranslationX),
                ObjectAnimator.ofFloat(mResultEditText, View.TRANSLATION_Y, resultTranslationY),
                ObjectAnimator.ofFloat(mFormulaEditText, View.TRANSLATION_Y, formulaTranslationY));
        animatorSet.setDuration(getResources().getInteger(android.R.integer.config_longAnimTime));
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.addListener(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                // Reset all of the values modified during the animation.
                mResultEditText.setPivotY(mResultEditText.getHeight() / 2);
                mResultEditText.setTextColor(resultTextColor);
                mResultEditText.setScaleX(1.0f);
                mResultEditText.setScaleY(1.0f);
                mResultEditText.setTranslationX(0.0f);
                mResultEditText.setTranslationY(0.0f);
                mFormulaEditText.setTranslationY(0.0f);

                // Finally update the formula to use the current result.
                mFormulaEditText.setText(result);
                setState(CalculatorState.RESULT);
            }
        });

        play(animatorSet);
    }

    protected CalculatorExpressionEvaluator getEvaluator() {
        return mEvaluator;
    }

    private enum Panel {
        Advanced, Hex, Matrix
    }


    // COLLAPSE HIERARCHY


}
