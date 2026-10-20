package io.github.felixlp.libkat;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import io.github.felixlp.libkat.core.Auth;
import io.github.felixlp.libkat.core.Text;
import io.github.felixlp.libkat.core.Token;

public class LoginActivity extends Activity {
    private EditText login;
    private EditText password;
    private Button submit;
    private Button logout;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        login = (EditText) findViewById(R.id.login);
        password = (EditText) findViewById(R.id.password);
        submit = (Button) findViewById(R.id.submit);
        logout = (Button) findViewById(R.id.logout);
        status = (TextView) findViewById(R.id.status);

        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });
        logout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                App.get().auth().logout();
                showState();
            }
        });
        showState();
    }

    private void showState() {
        Token t = App.get().auth().token();
        if (t == null) {
            status.setText("");
            logout.setVisibility(View.GONE);
        } else {
            status.setText("Вы вошли как " + (Text.isEmpty(t.username) ? "#" + t.userId : t.username));
            logout.setVisibility(View.VISIBLE);
        }
    }

    private void doLogin() {
        final String l = login.getText().toString().trim();
        final String p = password.getText().toString();
        if (l.length() == 0 || p.length() == 0) {
            status.setText("Введите логин и пароль");
            return;
        }
        submit.setEnabled(false);
        status.setText("…");
        final Auth auth = App.get().auth();
        Bg.run(new Bg.Job<Token>() {
            @Override
            public Token run() throws Exception {
                return auth.login(l, p, new Auth.Progress() {
                    @Override
                    public void step(final String message) {
                        Bg.MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                status.setText(message);
                            }
                        });
                    }
                });
            }
        }, new Bg.Done<Token>() {
            @Override
            public void done(Token result, Exception error) {
                if (isFinishing()) return;
                submit.setEnabled(true);
                if (error != null) {
                    status.setText("Не удалось войти: " + Bg.message(error));
                    return;
                }
                password.setText("");
                showState();
                finish();
            }
        });
    }
}
