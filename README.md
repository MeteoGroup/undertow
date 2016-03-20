
# Undertow pimped with brotli compression support

Experimental revise of [Undertow](http://undertow.io/)
to provide Google's [brotli](https://github.com/google/brotli) compression.

It uses [jbrotli](https://github.com/nitram509/jbrotli) Java implementation.


##### Status of this project

❌ **CANCELED**

The original idea was to improve Undertow code base with Brotli support.
This idea was dropped in favor of a generic servlet filter implementation.

If you wanna use Brotli compression in your web application, have a look
at the [BrotliServletFilter](https://github.com/meteogroup/jbrotli) in jbrotli

Now, this code is just meant for being there as learning exercise.
