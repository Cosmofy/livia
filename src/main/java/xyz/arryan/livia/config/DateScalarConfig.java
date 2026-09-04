package xyz.arryan.livia.config;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsRuntimeWiring;
import graphql.scalars.ExtendedScalars;
import graphql.schema.idl.RuntimeWiring;

@DgsComponent
public class DateScalarConfig {

    @DgsRuntimeWiring
    public RuntimeWiring.Builder registerDateScalars(RuntimeWiring.Builder builder) {
        return builder
                .scalar(ExtendedScalars.Date)
                .scalar(ExtendedScalars.DateTime);
    }
}
