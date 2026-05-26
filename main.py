import os
from kivy.app import App
from kivy.utils import platform

# 只有在安卓环境下才导入原生组件
if platform == 'android':
    from jnius import autoclass, cast

    # 导入安卓原生类
    PythonActivity = autoclass('org.kivy.android.PythonActivity')
    Context = autoclass('android.content.Context')
    Intent = autoclass('android.content.Intent')
    Settings = autoclass('android.provider.Settings')
    Uri = autoclass('android.net.Uri')

    WindowManager = autoclass('android.view.WindowManager')
    LayoutParams = autoclass('android.view.WindowManager$LayoutParams')
    Button = autoclass('android.widget.Button')
    TextView = autoclass('android.widget.TextView')
    LinearLayout = autoclass('android.widget.LinearLayout')
    Color = autoclass('android.graphics.Color')
    PixelFormat = autoclass('android.graphics.PixelFormat')
    Gravity = autoclass('android.view.Gravity')
else:
    # 电脑端测试用的占位符
    PythonActivity = None


# ==========================================
# 你的图片解析函数
# ==========================================
def your_parse_function(image_path):
    # 模拟解析逻辑
    return "解析成功！\n[这里是悬浮窗显示的解析文本内容]"


class FloatingWindowApp(App):
    def build(self):
        if platform == 'android':
            self.activity = PythonActivity.mActivity
            # 1. 检查并申请悬浮窗权限
            if not Settings.canDrawOverlays(self.activity):
                intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.setData(Uri.parse(f"package:{self.activity.getPackageName()}"))
                self.activity.startActivity(intent)

            # 2. 创建原生的悬浮按钮
            self.create_floating_button()
        else:
            print("悬浮窗功能仅支持在安卓真机上运行。")

        return None  # 主界面不需要 Kivy 渲染任何东西，或者只显示一个说明标签

    def create_floating_button(self):
        self.window_manager = cast(WindowManager, self.activity.getSystemService(Context.WINDOW_SERVICE))

        # 配置悬浮按钮的参数
        # TYPE_APPLICATION_OVERLAY 是安卓 8.0 及以上推荐的悬浮窗类型
        TYPE_APPLICATION_OVERLAY = 2038

        params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE,  # 不影响底下屏幕的点击
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP | Gravity.LEFT
        params.x = 100
        params.y = 100

        # 创建原生按钮
        self.float_button = Button(self.activity)
        self.float_button.setText("截屏解析")
        self.float_button.setBackgroundColor(Color.parseColor("#33B5E5"))

        # 绑定点击事件（使用 Pyjnius 的 Python 监听器）
        from android.runnable import Runnable
        class ClickListener(autoclass('android.view.View$OnClickListener')):
            def __init__(self, callback):
                super(ClickListener, self).__init__()
                self.callback = callback

            def onClick(self, view):
                self.callback()

        self.float_button.setOnClickListener(ClickListener(self.on_screenshot_click))

        # 将按钮添加到屏幕上
        self.window_manager.addView(self.float_button, params)

    def on_screenshot_click(self):
        """点击悬浮按钮触发的事件"""
        # 注意：因为变成了全局悬浮窗，Kivy内置的 Window.export_to_png 只能截自己的黑屏
        # 如果要截取全屏（包含别的App），需要通过安卓 MediaProjectionManager 录屏流截取，
        # 或者是 root 权限下执行 screencap。
        # 这里假设你已经有了截屏图片，或者通过底层拿到了路径：
        screenshot_path = "/sdcard/Download/screenshot.png"

        # 执行你的解析函数
        result_text = your_parse_function(screenshot_path)

        # 弹出结果悬浮窗
        self.show_result_window(result_text)

    def show_result_window(self, text):
        """展示解析结果的悬浮窗"""
        TYPE_APPLICATION_OVERLAY = 2038
        params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            500,  # 悬浮窗高度
            TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM

        # 创建布局和文本框
        layout = LinearLayout(self.activity)
        layout.setBackgroundColor(Color.parseColor("#CC000000"))  # 半透明黑

        text_view = TextView(self.activity)
        text_view.setText(text)
        text_view.setTextColor(Color.WHITE)
        text_view.setTextSize(16.0)

        layout.addView(text_view)

        # 将结果面板添加到屏幕
        self.window_manager.addView(layout, params)


if __name__ == '__main__':
    FloatingWindowApp().run()