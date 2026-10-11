package com.mineways;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

/**
 * 标签页承载：一个 Fragment 服务于导航图里的 5 个目的地。
 *
 * <p>页码以「自己所在的目的地 id」为准（{@code page_all → 0 … page_about → 4}），
 * argument {@code index} 只作兜底 —— 这样即使某个目的地的默认参数没生效，
 * 也不会出现"第二页显示成别的页 / 少一块界面"的问题。</p>
 *
 * <p>页面本体仍在 {@link MainActivity#buildPage(int)} 里构造（原有构造方法一行没改），
 * 切页由官方 NavController 完成：launchSingleTop + restoreState 保证不压栈、状态还在，
 * 与玄戒工具箱用 Navigation Compose 的行为一致。</p>
 */
public class PageFragment extends Fragment {

    /** nav_graph.xml 里的 argument 名（兜底用）。 */
    public static final String ARG_INDEX = "index";

    private View root;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        MainActivity activity = (MainActivity) requireActivity();
        root = activity.buildPage(activity.pageIndexOf(destinationId()));
        activity.registerPageView(root);      // 登记后底部让位高度会跟着玻璃胶囊走
        return root;
    }

    /** 自己所在的目的地 id；实在拿不到就退回 argument。 */
    private int destinationId() {
        try {
            NavController controller = NavHostFragment.findNavController(this);
            NavDestination destination = controller.getCurrentDestination();
            if (destination != null) {
                return destination.getId();
            }
        } catch (Throwable ignored) {
        }
        Bundle args = getArguments();
        return args == null ? 0 : args.getInt(ARG_INDEX, 0);
    }

    @Override
    public void onDestroyView() {
        if (root != null) {
            ((MainActivity) requireActivity()).unregisterPageView(root);
            root = null;
        }
        super.onDestroyView();
    }
}
