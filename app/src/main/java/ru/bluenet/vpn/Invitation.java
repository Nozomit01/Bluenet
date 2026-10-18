package ru.bluenet.vpn;
import android.net.Uri;
/** Passwords are optional and stay in the URI fragment, never the URL host/query or logs. */
final class Invitation {
    static String make(String name,String password){
        validate(name,password);
        String fragment=new Uri.Builder().appendQueryParameter("name",name).appendQueryParameter("password",password).build().getEncodedQuery();
        return "bluenet://join#"+fragment;
    }
    static String[] parse(String text){
        if(text==null||text.length()>4096)throw new IllegalArgumentException();Uri u=Uri.parse(text);
        if(!"bluenet".equals(u.getScheme())||!"join".equals(u.getHost())||u.getEncodedQuery()!=null||u.getUserInfo()!=null||u.getPort()!=-1||u.getFragment()==null)throw new IllegalArgumentException();
        Uri fragment=Uri.parse("https://invitation.invalid/?"+u.getEncodedFragment());
        if(fragment.getQueryParameters("name").size()!=1||fragment.getQueryParameters("password").size()!=1)throw new IllegalArgumentException();
        String name=fragment.getQueryParameter("name"),password=fragment.getQueryParameter("password");validate(name,password);return new String[]{name,password};
    }
    private static void validate(String name,String password){if(name==null||password==null||name.trim().isEmpty()||name.length()>255||password.length()>256||!password.isEmpty()&&password.length()<6||name.codePoints().anyMatch(Character::isISOControl)||password.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();}
}
