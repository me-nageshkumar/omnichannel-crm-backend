package me.nageshk.omnichannel.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Deep links into the console (for example {@code /profile} or {@code /login/verify}) are client-side
 * routes; the server answers them with {@code index.html}. Only paths up to three segments deep whose
 * segments contain no dot, and whose first segment is not {@code api} or {@code ws}, are forwarded, so
 * static files and unknown API paths keep their own 404s. {@code /} itself is Spring Boot's welcome page.
 */
@Controller
public class SpaForwardController {

    private static final String FIRST = "{first:(?!api$|ws$)[^.]*}";

    @GetMapping({
        "/" + FIRST,
        "/" + FIRST + "/{second:[^.]*}",
        "/" + FIRST + "/{second:[^.]*}/{third:[^.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
