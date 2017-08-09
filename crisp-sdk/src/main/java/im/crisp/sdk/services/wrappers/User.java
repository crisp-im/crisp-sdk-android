package im.crisp.sdk.services.wrappers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import im.crisp.sdk.SharedCrisp;

/**
 * Created by baptistejamin on 25/05/2017.
 */


public class User {
    SharedCrisp crisp;

    public User(SharedCrisp crisp) {
        this.crisp = crisp;
    }

    public String getEmail() {
        return crisp.getContextStore().getSession().email;
    }

    public String getPhone() {
        return crisp.getContextStore().getSession().phone;
    }

    public String getNickname() {
        return crisp.getContextStore().getSession().nickname;
    }

    public String getAvatar() {
        return crisp.getContextStore().getSession().avatar;
    }

    public JSONObject getData() {
        return crisp.getContextStore().getSession().data;
    }

    public void setEmail(String email) {
        if (crisp.getContextStore().getSession() != null) {
            crisp.getContextStore().getSession().email = email;
        }
        crisp.getContextStore().saveSession();
        crisp.getSocket().getSession().setEmail(email);
    }

    public void setPhone(String phone) {
        if (crisp.getContextStore().getSession() != null) {
            crisp.getContextStore().getSession().phone = phone;
        }
        crisp.getContextStore().saveSession();
        crisp.getSocket().getSession().setPhone(phone);
    }

    public void setNickname(String nickname) {
        if (crisp.getContextStore().getSession() != null) {
            crisp.getContextStore().getSession().nickname = nickname;
        }
        crisp.getContextStore().saveSession();
        crisp.getSocket().getSession().setNickname(nickname);
    }

    public void setAvatar(String avatar) {
        if (crisp.getContextStore().getSession() != null) {
            crisp.getContextStore().getSession().avatar = avatar;
        }
        crisp.getContextStore().saveSession();
        crisp.getSocket().getSession().setAvatar(avatar);
    }

    public void setData(JSONObject data) {
        if (crisp.getContextStore().getSession() != null) {
            crisp.getContextStore().getSession().data = data;
        }
        crisp.getContextStore().saveSession();
        crisp.getSocket().getSession().setData(data);
    }

    public void setSegments(List<String> segments) {
        crisp.getSocket().getSession().setSegments(new JSONArray(segments));
    }
}
