package io.github.colorduo.update;

import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import java.util.regex.*;

final class UpdateMarkdown {
    private static final Pattern INLINE=Pattern.compile("(!?\\[[^\\]\\n]+\\]\\(https?://[^\\s)]+\\))|(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(\\*[^*\\n]+\\*)");
    static CharSequence render(String input) {
        try {
            SpannableStringBuilder out=new SpannableStringBuilder();boolean code=false;
            for(String line:input.split("\\n",-1)) {
                if(line.startsWith("```")){code=!code;continue;}
                int start=out.length();
                if(code){out.append(line).append('\n');out.setSpan(new TypefaceSpan("monospace"),start,out.length(),33);continue;}
                boolean heading=line.matches("^#{1,6} .*"),quote=line.startsWith("> ");
                if(heading)line=line.replaceFirst("^#{1,6} +","");
                if(quote)line=line.substring(2);
                if(line.matches("^[-*_]{3,}$"))line="────────";
                line=line.replaceFirst("^\\s*[-*+] +","• ");
                Matcher m=INLINE.matcher(line);int pos=0;
                while(m.find()) {
                    out.append(line,pos,m.start());String token=m.group();int begin=out.length();
                    if(token.startsWith("![")){out.append(token.substring(2,token.indexOf(']')));}
                    else if(token.startsWith("[")){int split=token.indexOf("](");out.append(token.substring(1,split));out.setSpan(new URLSpan(token.substring(split+2,token.length()-1)),begin,out.length(),33);}
                    else if(token.startsWith("`")){out.append(token.substring(1,token.length()-1));out.setSpan(new TypefaceSpan("monospace"),begin,out.length(),33);}
                    else {boolean bold=token.startsWith("**");int trim=bold?2:1;out.append(token.substring(trim,token.length()-trim));out.setSpan(new StyleSpan(bold?Typeface.BOLD:Typeface.ITALIC),begin,out.length(),33);}
                    pos=m.end();
                }
                out.append(line,pos,line.length()).append('\n');
                if(heading){out.setSpan(new StyleSpan(Typeface.BOLD),start,out.length(),33);out.setSpan(new RelativeSizeSpan(1.15f),start,out.length(),33);}
                if(quote)out.setSpan(new QuoteSpan(),start,out.length(),33);
            }
            return out;
        }catch(Exception e){return input;}
    }
}
