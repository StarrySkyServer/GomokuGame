package top.gomoku.ui;

import cn.nukkit.Player;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowModal;

/**
 * 确认/取消弹窗封装（参考 PluginDemo 模板实现）。
 * 按钮 0 为确认（trueButton），按钮 1 为取消（falseButton）。
 */
public class Modal {

    private final FormWindowModal form;
    private Runnable truer;
    private Runnable falser;
    private Runnable close;

    public Modal(String title, String content, String trueText, String falseText) {
        this.form = new FormWindowModal(title, content, trueText, falseText);
    }

    public Modal truer(Runnable truer) {
        this.truer = truer;
        return this;
    }

    public Modal falser(Runnable falser) {
        this.falser = falser;
        return this;
    }

    public Modal onClose(Runnable close) {
        this.close = close;
        return this;
    }

    public void show(Player player) {
        this.form.addHandler(FormResponseHandler.withoutPlayer(ignored -> this.process()));
        player.showFormWindow(this.form);
    }

    private void process() {
        if (this.form.wasClosed()) {
            if (this.close != null) {
                this.close.run();
            }
            return;
        }
        if (this.form.getResponse().getClickedButtonId() == 0) {
            if (this.truer != null) {
                this.truer.run();
            }
        } else if (this.falser != null) {
            this.falser.run();
        }
    }
}