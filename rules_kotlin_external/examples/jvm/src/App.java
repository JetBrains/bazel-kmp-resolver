package example;

/**
 * Compiles against a KMP library resolved by rules_kotlin_external, proving the classpath wiring:
 * the resolved jar reached the compile classpath through the generated hub repository.
 */
public class App {
    public static void main(String[] arguments) {
        // A compile-time reference into the resolved kotlinx-coroutines jar.
        Class<?> coroutineScope = kotlinx.coroutines.CoroutineScope.class;
        System.out.println(coroutineScope.getName());
    }
}
