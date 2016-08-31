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
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.android2.calculator3.CalculatorExpressionEvaluator.EvaluateCallback;
import com.android2.calculator3.util.TextUtil;
import com.android2.calculator3.view.DisplayOverlay;
import com.android2.calculator3.view.FormattedNumberEditText;
import com.android2.calculator3.view.GraphView;
import com.xlythe.floatingview.AnimationFinishedListener;
import com.xlythe.math.Base;
import com.xlythe.math.GraphModule;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Adds graphing and base switching to the basic calculator.
 * */
public abstract class HexCalculator extends BasicCalculator {

    // instance state keys
    private static final String KEY_BASE = NAME + "_base";

    private TextView mResultEditText;

    private TextView mInfoView;
    private boolean mShowBaseDetails;
    private boolean mShowTrigDetails;
    private NumberBaseManager mBaseManager;
    private DisplayOverlay mDisplayView;
    private FormattedNumberEditText mFormulaEditText;
    private String mX;
    private GraphView mMiniGraph;
    private View mGraphButtons;
    private ListView mActiveEquationsListView;
    private BaseAdapter mCurrentGraphsAdapter;
    private GraphController mGraphController;
    private boolean mMarkAsCleared = false;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_BASE, mBaseManager.getNumberBase().ordinal());
    }

    @Override
    protected void initialize(Bundle savedInstanceState) {
        super.initialize(savedInstanceState);
        mInfoView = (TextView) findViewById(R.id.info);
        mFormulaEditText = (FormattedNumberEditText) findViewById(R.id.formula);
        mResultEditText = (TextView) findViewById(R.id.result);

        Base base = Base.DECIMAL;
        int baseOrdinal = savedInstanceState.getInt(KEY_BASE, -1);
        if (baseOrdinal != -1) {
            base = Base.values()[baseOrdinal];
        }
        mBaseManager = new NumberBaseManager(base);
        invalidateSelectedBase(base);

        mShowBaseDetails = !mBaseManager.getNumberBase().equals(Base.DECIMAL);
        mShowTrigDetails = false;

        invalidateDetails();

        // Load up all are variables (find views, find out what 'X' is called)
        mX = getString(R.string.var_x);
        mDisplayView = (DisplayOverlay) findViewById(R.id.display);
        mFormulaEditText = (FormattedNumberEditText) findViewById(R.id.formula);
        mMiniGraph = (GraphView) findViewById(R.id.mini_graph);
        mGraphButtons = findViewById(R.id.graph_buttons);
        mActiveEquationsListView = (ListView) findViewById(R.id.current_graphs);

        mGraphController = new GraphController(new GraphModule(getEvaluator().getSolver()), mMiniGraph);

        mMiniGraph.setOnCenterListener(new GraphView.OnCenterListener() {
            @Override
            public void onCentered() {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mMiniGraph.getLayoutParams();
                int displayHeight = getResources().getDimensionPixelSize(R.dimen.display_height_with_shadow);

                // Move the X axis so it lines up to the top of the view (so we have a good starting point)
                float initialY = -mMiniGraph.getHeight() / 2;
                // Now move it up to the top of the phone
                initialY -= params.topMargin;
                // Move the X axis down so it matches the bottom of the display
                initialY += displayHeight;
                // Move it up 50% between the formula and the end of the display
                initialY -= (displayHeight - mFormulaEditText.getHeight()) / 2;

                mMiniGraph.panBy(0, initialY);
            }
        });
        invalidateInlineBounds();
        mGraphButtons.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (android.os.Build.VERSION.SDK_INT < 16) {
                    mGraphButtons.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                } else {
                    mGraphButtons.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
                mGraphButtons.setTranslationY(mGraphButtons.getHeight());
                mMiniGraph.zoomReset();

            }
        });

        mCurrentGraphsAdapter = new ArrayAdapter<GraphView.Graph>(getBaseContext(), R.layout.graph_entry, mGraphController.getGraphs()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    LayoutInflater inflater = getLayoutInflater();
                    convertView = inflater.inflate(R.layout.graph_entry, parent, false);
                }

                final GraphView.Graph item = getItem(position);

                String formula = item.getFormula();
                formula = formula.replace(mX, mX.toLowerCase(Locale.getDefault()));

                TextView textView = (TextView) convertView.findViewById(android.R.id.text1);
                textView.setText(String.format("f%s(%s)=%s", (position + 1), mX.toLowerCase(Locale.getDefault()), formula));

                return convertView;
            }
        };
        mActiveEquationsListView.setAdapter(mCurrentGraphsAdapter);
    }

    private void transitionToGraph() {
        mDisplayView.transitionToGraph(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                setState(CalculatorState.GRAPHING);
            }
        });
    }

    private void transitionToDisplay() {
        mDisplayView.transitionToDisplay(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                CalculatorState newState = getState() ==
                        CalculatorState.GRAPHING ? CalculatorState.INPUT : getState();
                setState(newState);

                if (mMarkAsCleared) {
                    mMarkAsCleared = false;
                    mGraphController.clear();
                }
            }
        });
    }

    private void enlargeGraph() {
        mDisplayView.expandGraph();
        mGraphButtons.animate().translationY(0).setListener(null);
    }

    private void shrinkGraph() {
        shrinkGraph(null);
    }

    private void shrinkGraph(Animator.AnimatorListener listener) {
        mDisplayView.collapseGraph();
        mGraphButtons.animate().translationY(mGraphButtons.getHeight()).setListener(listener);
    }

    private void invalidateInlineBounds() {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mMiniGraph.getLayoutParams();
        params.topMargin = getResources().getDimensionPixelSize(R.dimen.display_height_graph_expanded);
        params.topMargin -= getResources().getDimensionPixelSize(R.dimen.display_shadow);
        params.bottomMargin = mGraphButtons.getMeasuredHeight();
        params.bottomMargin -= getResources().getDimensionPixelSize(R.dimen.display_shadow);

        mMiniGraph.setLayoutParams(params);
    }

    protected void invalidateDetails() {
        List<Detail> details = getDetails();

        String text = "";
        for (Detail detail : details) {
            if (!text.isEmpty()) {
                text += " | ";
            }
            text += detail.word;
        }

        if(mInfoView != null) {
            mInfoView.setMovementMethod(LinkMovementMethod.getInstance());
            mInfoView.setText(text, TextView.BufferType.SPANNABLE);
            for (Detail detail : details) {
                if (detail.listener != null) {
                    setClickableSpan(mInfoView, detail.word, detail.listener);
                }
            }
        }
    }

    protected List<Detail> getDetails() {
        List<Detail> details = new LinkedList<>();
        if (mShowBaseDetails) {
            String text = "";
            switch(mBaseManager.getNumberBase()) {
                case HEXADECIMAL:
                    text = getString(R.string.hex).toUpperCase(Locale.getDefault());
                    break;
                case BINARY:
                    text = getString(R.string.bin).toUpperCase(Locale.getDefault());
                    break;
                case DECIMAL:
                    text = getString(R.string.dec).toUpperCase(Locale.getDefault());
                    break;
            }

            View.OnClickListener listener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int DEC = 0;
                    final int HEX = 1;
                    final int BIN = 2;
                    final PopupMenu popupMenu = new PopupMenu(getBaseContext(), mInfoView);
                    final Menu menu = popupMenu.getMenu();
                    menu.add(0, DEC, menu.size(), R.string.desc_dec);
                    menu.add(0, HEX, menu.size(), R.string.desc_hex);
                    menu.add(0, BIN, menu.size(), R.string.desc_bin);
                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            switch (item.getItemId()) {
                                case DEC:
                                    // Update the BaseManager, which handles restricting which buttons to show
                                    mBaseManager.setNumberBase(Base.DECIMAL);
                                    mShowBaseDetails = true;

                                    // Update the evaluator, which handles the math
                                    getEvaluator().setBase(mFormulaEditText.getCleanText(), Base.DECIMAL, new EvaluateCallback() {
                                        @Override
                                        public void onEvaluate(String expr, String result, int errorResourceId) {
                                            if (errorResourceId != INVALID_RES_ID) {
                                                onError(errorResourceId);
                                            } else {
                                                mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
                                                onResult(result);
                                            }
                                        }
                                    });
                                    invalidateSelectedBase(Base.DECIMAL);
                                    break;
                                case HEX:
                                    // Update the BaseManager, which handles restricting which buttons to show
                                    mBaseManager.setNumberBase(Base.HEXADECIMAL);
                                    mShowBaseDetails = true;

                                    // Update the evaluator, which handles the math
                                    getEvaluator().setBase(mFormulaEditText.getCleanText(), Base.HEXADECIMAL, new EvaluateCallback() {
                                        @Override
                                        public void onEvaluate(String expr, String result, int errorResourceId) {
                                            if (errorResourceId != INVALID_RES_ID) {
                                                onError(errorResourceId);
                                            } else {
                                                mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
                                                onResult(result);
                                            }
                                        }
                                    });
                                    invalidateSelectedBase(Base.HEXADECIMAL);
                                    break;
                                case BIN:
                                    // Update the BaseManager, which handles restricting which buttons to show
                                    mBaseManager.setNumberBase(Base.BINARY);
                                    mShowBaseDetails = true;

                                    // Update the evaluator, which handles the math
                                    getEvaluator().setBase(mFormulaEditText.getCleanText(), Base.BINARY, new EvaluateCallback() {
                                        @Override
                                        public void onEvaluate(String expr, String result, int errorResourceId) {
                                            if (errorResourceId != INVALID_RES_ID) {
                                                onError(errorResourceId);
                                            } else {
                                                mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
                                                onResult(result);
                                            }
                                        }
                                    });
                                    invalidateSelectedBase(Base.BINARY);
                                    break;
                            }
                            return true;
                        }
                    });
                    popupMenu.show();
                }
            };
            details.add(new Detail(text, listener));
        }
        if (mShowTrigDetails) {
            String text = CalculatorSettings.useRadians(getBaseContext()) ?
                    getString(R.string.radians) : getString(R.string.degrees);

            View.OnClickListener listener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int RAD = 0;
                    final int DEG = 1;
                    final PopupMenu popupMenu = new PopupMenu(getBaseContext(), mInfoView);
                    final Menu menu = popupMenu.getMenu();
                    menu.add(0, RAD, menu.size(), R.string.radians);
                    menu.add(0, DEG, menu.size(), R.string.degrees);
                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            switch (item.getItemId()) {
                                case RAD:
                                    CalculatorSettings.setRadiansEnabled(getBaseContext(), true);
                                    break;
                                case DEG:
                                    CalculatorSettings.setRadiansEnabled(getBaseContext(), false);
                                    break;
                            }
                            invalidateDetails();
                            if (getState() != CalculatorState.GRAPHING) {
                                setState(CalculatorState.INPUT);
                            }
                            getEvaluator().evaluate(mFormulaEditText.getCleanText(), HexCalculator.this);
                            return true;
                        }
                    });
                    popupMenu.show();
                }
            };
            details.add(new Detail(text, listener));
        }
        return details;
    }

    private void setClickableSpan(TextView textView, final String word, final View.OnClickListener listener) {
        final Spannable spans = (Spannable) textView.getText();
        String text = spans.toString();
        ClickableSpan span = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                Selection.setSelection(spans, 0);
                listener.onClick(null);
            }

            public void updateDrawState(@NonNull TextPaint ds) {}
        };
        spans.setSpan(span, text.indexOf(word), text.indexOf(word) + word.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @Override
    public void onButtonClick(View view) {
        switch (view.getId()) {
            case R.id.eq:
                mMarkAsCleared = true;
                break;
            case R.id.mini_graph:
                if (mDisplayView.isGraphExpanded()) {
                    shrinkGraph();
                } else {
                    enlargeGraph();
                }
                return;
            case R.id.btn_zoom_in:
                mMiniGraph.zoomIn();
                return;
            case R.id.btn_zoom_out:
                mMiniGraph.zoomOut();
                return;
            case R.id.btn_zoom_reset:
                mMiniGraph.zoomReset();

                return;
            case R.id.btn_close_graph:
                shrinkGraph();
                return;
        }
        switch (view.getId()) {
            case R.id.fun_cos:
            case R.id.fun_sin:
            case R.id.fun_tan:
                mShowTrigDetails = true;
                invalidateDetails();
                break;
            case R.id.hex:
                // Update the BaseManager, which handles restricting which buttons to show
                mBaseManager.setNumberBase(Base.HEXADECIMAL);
                mShowBaseDetails = true;

                // Update the evaluator, which handles the math
                getEvaluator().setBase(mFormulaEditText.getCleanText(), Base.HEXADECIMAL, new EvaluateCallback() {
                    @Override
                    public void onEvaluate(String expr, String result, int errorResourceId) {
                        if (errorResourceId != INVALID_RES_ID) {
                            onError(errorResourceId);
                        } else {
                            mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
                            onResult(result);
                        }
                    }
                });
                invalidateSelectedBase(Base.HEXADECIMAL);
                return;
            case R.id.bin:
                // Update the BaseManager, which handles restricting which buttons to show
                mBaseManager.setNumberBase(Base.BINARY);
                mShowBaseDetails = true;

                // Update the evaluator, which handles the math
                getEvaluator().setBase(mFormulaEditText.getCleanText(), Base.BINARY, new EvaluateCallback() {
                    @Override
                    public void onEvaluate(String expr, String result, int errorResourceId) {
                        if (errorResourceId != INVALID_RES_ID) {
                            onError(errorResourceId);
                        } else {
                            mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
                            onResult(result);
                        }
                    }
                });
                invalidateSelectedBase(Base.BINARY);
                return;
            case R.id.dec:
                // Update the BaseManager, which handles restricting which buttons to show
                mBaseManager.setNumberBase(Base.DECIMAL);
                mShowBaseDetails = true;

                // Update the evaluator, which handles the math
                getEvaluator().setBase(mFormulaEditText.getCleanText(), Base.DECIMAL, new EvaluateCallback() {
                    @Override
                    public void onEvaluate(String expr, String result, int errorResourceId) {
                        if (errorResourceId != INVALID_RES_ID) {
                            onError(errorResourceId);
                        } else {
                            mResultEditText.setText(TextUtil.formatText(result, mFormulaEditText.getEquationFormatter(), mFormulaEditText.getSolver()));
                            onResult(result);
                        }
                    }
                });
                invalidateSelectedBase(Base.DECIMAL);
                return;
        }
        super.onButtonClick(view);
    }

    @Override
    public boolean onLongClick(View view) {
        switch (view.getId()) {
            case R.id.fun_cos:
            case R.id.fun_sin:
            case R.id.fun_tan:
                mShowTrigDetails = true;
                invalidateDetails();
                break;
        }
        return super.onLongClick(view);
    }

    private void invalidateSelectedBase(Base base) {
        findViewById(R.id.hex).setSelected(base.equals(Base.HEXADECIMAL));
        findViewById(R.id.bin).setSelected(base.equals(Base.BINARY));
        findViewById(R.id.dec).setSelected(base.equals(Base.DECIMAL));

        // Disable any buttons that are not relevant to the current base
        for (int resId : mBaseManager.getViewIds()) {
            View view = findViewById(resId);
            if (view != null) {
                view.setEnabled(!mBaseManager.isViewDisabled(resId));
            }
        }

        invalidateDetails();
    }

    @Override
    public void onBackPressed() {
        if (mDisplayView.isGraphExpanded()) {
            shrinkGraph();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onClear() {
        mMarkAsCleared = true;
        super.onClear();
    }

    @Override
    public void onEvaluate(String expr, String result, int errorResourceId) {
        if (getState() == CalculatorState.EVALUATE && expr.contains(mX)) {
            mEvaluator.saveHistory(expr, "", false, this);
            incrementGroupId();
            mDisplayView.scrollToMostRecent();
            onResult(result);
        } else {
            super.onEvaluate(expr, result, errorResourceId);
        }

        if (expr.contains(mX)) {
            transitionToGraph();

            String formula = mDisplayView.getNormalizedExpression(mFormulaEditText.cleanExpression(mFormulaEditText.getCleanText(), HexCalculator.this), this);

            if (mGraphController.getGraphs().size() == 0) {
                mGraphController.addNewGraph(formula);
            } else {
                mGraphController.changeLatestGraph(formula);
            }

            notifyDataSetChanged();
        } else {
            transitionToDisplay();
        }
    }

    @Override
    protected void onDestroy() {
        if (mGraphController != null) {
            mGraphController.destroy();
            mGraphController = null;
        }
        super.onDestroy();
    }

    private void notifyDataSetChanged() {
        mCurrentGraphsAdapter.notifyDataSetChanged();
        mGraphButtons.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (android.os.Build.VERSION.SDK_INT < 16) {
                    mGraphButtons.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                } else {
                    mGraphButtons.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
                mGraphButtons.setTranslationY(mGraphButtons.getHeight());
                invalidateInlineBounds();
            }
        });
    }

    public static class Detail {
        public final String word;
        public final View.OnClickListener listener;

        public Detail(String word, View.OnClickListener listener) {
            this.word = word;
            this.listener = listener;
        }
    }
}
